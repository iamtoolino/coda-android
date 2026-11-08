package dev.coda.android

import org.junit.Assert.assertTrue
import org.junit.Test

/** The qaAuto gate never hides the PO's app from Android Auto, even when its id is passed as coda.qaApplicationId. */
class AutoListingBuildTest {
    @Test fun productionIdIsAlwaysAnAutoMediaSource() {
        if (BuildConfig.APPLICATION_ID == "dev.coda.android") assertTrue("dev.coda.android must stay listed", BuildConfig.AUTO_LISTED)
        else assertTrue("Test builds are listed only with -Pcoda.qaAuto=true", BuildConfig.AUTO_LISTED == (System.getProperty("codaQaAuto") == "true"))
    }
}
