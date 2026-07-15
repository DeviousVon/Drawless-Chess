package com.drawlesschess.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class LocaleConfigurationInstrumentedTest {
    @Test
    fun generatedDebugLocaleConfigAdvertisesSupportedAndPseudolocales() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resourceId = context.resources.getIdentifier(
            "_generated_res_locale_config",
            "xml",
            context.packageName,
        )
        assertNotEquals("Generated locale config is absent", 0, resourceId)

        val locales = linkedSetOf<String>()
        context.resources.getXml(resourceId).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    parser.getAttributeValue(ANDROID_NAMESPACE, "name")?.let(locales::add)
                }
                parser.next()
            }
        }

        assertEquals(
            setOf("en-US", "de", "es-419", "fr", "pt-BR", "en-XA", "ar-XB"),
            locales,
        )
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
