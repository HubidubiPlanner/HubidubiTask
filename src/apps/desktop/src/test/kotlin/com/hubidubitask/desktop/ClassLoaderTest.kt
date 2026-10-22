package com.hubidubitask.desktop

import kotlin.test.Test
import kotlin.test.assertNotNull

/** The Mac launcher on Java 27 starts the app without a context class loader; the fonts must still be found. */
class ClassLoaderTest {
    @Test
    fun `a start without a context class loader still finds the fonts, on this thread and the window's`() {
        var here: Any? = null
        var window: Any? = null
        val start = Thread {
            Thread.currentThread().contextClassLoader = null
            javax.swing.SwingUtilities.invokeAndWait { Thread.currentThread().contextClassLoader = null }
            useAppClassLoader()
            here = Thread.currentThread().contextClassLoader?.getResource("font/plex_sans_regular.ttf")
            javax.swing.SwingUtilities.invokeAndWait {
                window = Thread.currentThread().contextClassLoader?.getResource("font/plex_sans_semibold.ttf")
            }
        }
        start.start()
        start.join(10_000)
        assertNotNull(here, "main's thread")
        assertNotNull(window, "the window's thread")
    }
}
