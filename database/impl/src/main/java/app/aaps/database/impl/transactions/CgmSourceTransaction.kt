package app.aaps.database.impl.transactions

import app.aaps.database.entities.GlucoseValue
import app.aaps.database.entities.TherapyEvent
import app.aaps.database.transactions.TransactionGlucoseValue
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.round

/**
 * Inserts data from a CGM source into the database
 */
class CgmSourceTransaction(
    private val glucoseValues: List<TransactionGlucoseValue>,
    private val calibrations: List<Calibration>,
    private val sensorInsertionTime: Long?,
    private var bgReadings: List<GlucoseValue> = listOf(),
    private val updateWindow: Int = 10 // max number of smoothed glucose values to be updated per cycle to avoid updating the database with
    // all entries stored in bgReadings

) : Transaction<CgmSourceTransaction.TransactionResult>() {

    override fun run(): TransactionResult {
        val result = TransactionResult()
        glucoseValues.forEach {
            val current = database.glucoseValueDao.findByTimestampAndSensor(it.timestamp, it.sourceSensor)
            val glucoseValue = GlucoseValue(
                timestamp = it.timestamp,
                raw = it.raw,
                value = it.value,
                noise = it.noise,
                trendArrow = it.trendArrow,
                sourceSensor = it.sourceSensor,
                isValid = it.isValid,
                utcOffset = it.utcOffset
            ).also { gv ->
                gv.interfaceIDs.nightscoutId = it.nightscoutId
            }
            // if nsId is not provided in new record, copy from current if exists
            if (glucoseValue.interfaceIDs.nightscoutId == null)
                current?.let { existing -> glucoseValue.interfaceIDs.nightscoutId = existing.interfaceIDs.nightscoutId }
            // preserve invalidated status (user may delete record in UI)
            current?.let { existing -> glucoseValue.isValid = existing.isValid }
            // calculate smoothed values and update DB entries
            bgReadings += database.glucoseValueDao.compatGetBgReadingsDataFromTime(glucoseValue.timestamp - 125 * 60 * 1000, glucoseValue.timestamp) //MP Get all readings from up to 125 mins ago
                .blockingGet()
            bgReadings += glucoseValue
            bgReadings = smooth(bgReadings.reversed(), updateWindow) //reverse the list as the smoothing function expects the 0th entry to be the most recent one
            //bgReadings += database.glucoseValueDao.findByTimestampAndSensor(it.timestamp, it.sourceSensor)
            glucoseValue.raw = bgReadings[0].raw
            when {
                // new record, create new
                current == null                                                      -> {
                    database.glucoseValueDao.insertNewEntry(glucoseValue)
                    result.inserted.add(glucoseValue)
                }
                // different record, update
                !current.contentEqualsTo(glucoseValue)                               -> {
                    glucoseValue.id = current.id
                    database.glucoseValueDao.updateExistingEntry(glucoseValue)
                    result.updated.add(glucoseValue)
                }
                // update NS id if didn't exist and now provided
                current.interfaceIDs.nightscoutId == null && it.nightscoutId != null -> {
                    current.interfaceIDs.nightscoutId = it.nightscoutId
                    database.glucoseValueDao.updateExistingEntry(current)
                    result.updatedNsId.add(glucoseValue)
                }
            }
        }
        // Update the smoothed glucose values of the entries included in bgReadings
        bgReadings = bgReadings.reversed().takeLast(updateWindow) // reverse again so that in the DB, the newest values have the largest ID (for cosmetic and logical reasons :P). Also, keep only the last 10 entries to avoid unnecessarily updating older values that
        for (i in bgReadings) {
            database.glucoseValueDao.updateExistingEntry(i)
            result.updated.add(i)
        }
        calibrations.forEach {
            if (database.therapyEventDao.findByTimestamp(TherapyEvent.Type.FINGER_STICK_BG_VALUE, it.timestamp) == null) {
                val therapyEvent = TherapyEvent(
                    timestamp = it.timestamp,
                    type = TherapyEvent.Type.FINGER_STICK_BG_VALUE,
                    glucose = it.value,
                    glucoseUnit = it.glucoseUnit
                )
                database.therapyEventDao.insertNewEntry(therapyEvent)
                result.calibrationsInserted.add(therapyEvent)
            }
        }
        sensorInsertionTime?.let {
            if (database.therapyEventDao.findByTimestamp(TherapyEvent.Type.SENSOR_CHANGE, it) == null) {
                val therapyEvent = TherapyEvent(
                    timestamp = it,
                    type = TherapyEvent.Type.SENSOR_CHANGE,
                    glucoseUnit = TherapyEvent.GlucoseUnit.MGDL
                )
                database.therapyEventDao.insertNewEntry(therapyEvent)
                result.sensorInsertionsInserted.add(therapyEvent)
            }
        }
        return result
    }

    // insert DIAKEM smoothing
    private fun smooth(Data: List<GlucoseValue>, updateWindow: Int): List<GlucoseValue> {
        /**
         *  TSUNAMI DATA SMOOTHING CORE
         *
         *  Calculated a weighted average of 1st and 2nd order exponential smoothing functions
         *  to reduce the effect of sensor noise on APS performance. The weighted average
         *  is a compromise between the fast response to changing BGs at the cost of smoothness
         *  as offered by 1st order exponential smoothing, and the predictive, trend-sensitive but
         *  slower-to-respond smoothing as offered by 2nd order functions.
         *
         */
        val sizeRecords = Data.size
        if ( sizeRecords<2 || !Data[sizeRecords-1].sourceSensor.isLibre()
            || abs(Data[0].timestamp-Data[1].timestamp)>1.5*60000L) {
            return Data                            // only for Libre with 1 minute readings
        }

        val o1_sBG: ArrayList<Double> = ArrayList() //MP array for 1st order Smoothed Blood Glucose
        val o2_sBG: ArrayList<Double> = ArrayList() //MP array for 2nd order Smoothed Blood Glucose
        val o2_sD: ArrayList<Double> = ArrayList() //MP array for 2nd order Smoothed delta
        val ssBG: ArrayList<Double> = ArrayList() //MP array for weighted averaged, doubly smoothed Blood Glucose
        //val ssD: ArrayList<Double> = ArrayList() //MP array for deltas of doubly smoothed Blood Glucose
        var windowSize = 45 //MP number of bg readings to include in smoothing window; makes 45 m of 1m data
        val o1_weight = 0.4
        val o1_a = 0.5
        val o2_a = 0.4
        val o2_b = 1.0
        var insufficientSmoothingData = false

        // ADJUST SMOOTHING WINDOW TO ONLY INCLUDE VALID READINGS
        // Valid readings include:
        // - Values that actually exist (windowSize may not be larger than sizeRecords)
        // - Values that come in approx. every 5 min. If the time gap between two readings is larger, this is likely due to a sensor error or warmup of a new sensor.d
        // - Values that are not 38 mg/dl; 38 mg/dl reflects an xDrip error state (according to a comment in determine-basal.js)

        //MP: Adjust smoothing window if database size is smaller than the default value + 1 (+1 because the reading before the oldest reading to be smoothed will be used in the calculations
        if (sizeRecords <= windowSize) { //MP standard smoothing window
            windowSize =
                (sizeRecords - 1).coerceAtLeast(0) //MP Adjust smoothing window to the size of database if it is smaller than the original window size; -1 to always have at least one older value to compare against as a buffer to prevent app crashes
        }

        //MP: Adjust smoothing window further if a gap in the BG database is detected, e.g. due to sensor errors of sensor swaps, or if 38 mg/dl are reported (xDrip error state)
        for (i in 0 until windowSize) {
            if (Math.round((Data[i].timestamp - Data[i + 1].timestamp) / (1000.0 * 60)) > 2.5) { //MP: 2.5 min because a missed reading (i.e. readings coming in after 2  misses) can occur for various
                // reasons, like walking away from the phone or reinstalling AAPS
                //if (Math.round((data.get(i).date - data.get(i + 1).date) / 60000L) <= 7) { //MP crashes the app, useful for testing
                windowSize =
                    i + 1 //MP: If time difference between two readings exceeds 7 min, adjust windowSize to *include* the more recent reading (i = reading; +1 because windowSize reflects number of valid readings);
                break
            } else if (Data[i].value <= 39.0) {
                windowSize = i //MP: 38 mg/dl reflects an xDrip error state; Chain of valid readings ends here, *exclude* this value (windowSize = i; i + 1 would include the current value)
                break
            }
        }

        // CALCULATE SMOOTHING WINDOW - 1st order exponential smoothing
        o1_sBG.clear() // MP reset smoothed bg array

        if (windowSize >= 4) { //MP: Require a valid windowSize of at least 4 readings
            o1_sBG.add(Data[windowSize - 1].value) //MP: Initialise smoothing with the oldest valid data point
            for (i in 0 until windowSize) { //MP calculate smoothed bg window of valid readings
                o1_sBG.add(
                    0,
                    o1_sBG[0] + o1_a * (Data[windowSize - 1 - i].value - o1_sBG[0])
                ) //MP build array of 1st order smoothed bgs
            }
        } else {
            insufficientSmoothingData = true
        }

        // CALCULATE SMOOTHING WINDOW - 2nd order exponential smoothing
        if (windowSize >= 4) { //MP: Require a valid windowSize of at least 4 readings
            o2_sBG.add(Data[windowSize - 1].value) //MP Start 2nd order exponential data smoothing with the oldest valid bg
            o2_sD.add(Data[windowSize - 2].value - Data[windowSize - 1].value) //MP Start 2nd order exponential data smoothing with the oldest valid delta
            for (i in 0 until windowSize - 1) { //MP calculated smoothed bg window of last 1 h
                o2_sBG.add(
                    0,
                    o2_a * Data[windowSize - 2 - i].value + (1 - o2_a) * (o2_sBG[0] + o2_sD[0])
                ) //MP build array of 2nd order smoothed bgs; windowSize-1 is the oldest valid bg value, so windowSize-2 is from when on the smoothing begins;
                o2_sD.add(
                    0,
                    o2_b * (o2_sBG[0] - o2_sBG[1]) + (1 - o2_b) * o2_sD[0]
                ) //MP build array of 1st order smoothed bgs
            }
        } else {
            insufficientSmoothingData = true
        }

        // CALCULATE WEIGHTED AVERAGES OF GLUCOSE & DELTAS
        //ssBG.clear() // MP reset doubly smoothed bg array
        //ssD.clear() // MP reset doubly smoothed delta array

        if (!insufficientSmoothingData) { //MP Build doubly smoothed array only if there is enough valid readings
            for (i in o2_sBG.indices) { //MP calculated doubly smoothed bg of all o1/o2 smoothed data available; o2 & o1 smoothbg array sizes are equal in size, so only one is used as a condition here
                ssBG.add(o1_weight * o1_sBG[i] + (1 - o1_weight) * o2_sBG[i]) //MP build array of doubly smoothed bgs
            }
            /*
            for (i in 0 until ssBG.size - 1) {
                ssD.add(ssBG[i] - ssBG[i + 1]) //MP build array of doubly smoothed bg deltas
            }
             */
            for (i in 0 until minOf(ssBG.size, updateWindow)) { // noise at the beginning of the smoothing window is the greatest, so only include the 10 most recent values in the output
                Data[i].raw = max(round(ssBG[i]), 40.0) //Make 40 the smallest value as smaller values trigger errors (G6 error states < 40)
            }
        } else {
            for (i in 0 until minOf(Data.size, updateWindow)) { // noise at the beginning of the smoothing window is the greatest, so only include the 10 most recent values in the output
                Data[i].raw = max(Data[i].value, 40.0) // if insufficient smoothing data, copy 'value' into 'smoothed' data column so that it isn't empty; Make 40 the smallest value as smaller
                // values trigger errors (G6 error coding)
            }
        }

        return Data
    }
    // end of inserting DIAKEM

    data class Calibration(
        val timestamp: Long,
        val value: Double,
        val glucoseUnit: TherapyEvent.GlucoseUnit
    )

    class TransactionResult {

        val inserted = mutableListOf<GlucoseValue>()
        val updated = mutableListOf<GlucoseValue>()
        val updatedNsId = mutableListOf<GlucoseValue>()

        val calibrationsInserted = mutableListOf<TherapyEvent>()
        val sensorInsertionsInserted = mutableListOf<TherapyEvent>()

        fun all(): MutableList<GlucoseValue> =
            mutableListOf<GlucoseValue>().also { result ->
                result.addAll(inserted)
                result.addAll(updated)
            }
    }
}