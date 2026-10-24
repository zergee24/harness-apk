package com.harnessapk.system

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundPermissionSettingsTest {
    @Test
    fun honorAndHuaweiManufacturersGetVendorStartupGuide() {
        assertEquals(BackgroundPermissionGuide.HONOR_HUAWEI, backgroundPermissionGuide("HONOR"))
        assertEquals(BackgroundPermissionGuide.HONOR_HUAWEI, backgroundPermissionGuide("HUAWEI"))
        assertEquals(BackgroundPermissionGuide.HONOR_HUAWEI, backgroundPermissionGuide("Honor"))
    }

    @Test
    fun otherManufacturersFallBackToSystemPages() {
        assertEquals(BackgroundPermissionGuide.OTHER, backgroundPermissionGuide("Xiaomi"))
        assertEquals(BackgroundPermissionGuide.OTHER, backgroundPermissionGuide("samsung"))
        assertEquals(BackgroundPermissionGuide.OTHER, backgroundPermissionGuide("BigMe"))
    }
}
