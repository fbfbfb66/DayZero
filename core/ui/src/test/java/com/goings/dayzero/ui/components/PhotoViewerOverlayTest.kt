package com.goings.dayzero.ui.components

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PhotoViewerOverlayTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun initialIndexAndScaleUseProductionClamps() {
        assertEquals(0, PhotoViewerGeometry.clampInitialIndex(-5, 3))
        assertEquals(2, PhotoViewerGeometry.clampInitialIndex(5, 3))
        assertEquals(0, PhotoViewerGeometry.clampInitialIndex(0, 0))
        assertEquals(1f, PhotoViewerGeometry.clampScale(Float.NaN))
        assertEquals(1f, PhotoViewerGeometry.clampScale(0.5f))
        assertEquals(4f, PhotoViewerGeometry.clampScale(5f))
        assertEquals(2.5f, PhotoViewerGeometry.calculateDoubleTapScale(1f))
        assertEquals(1f, PhotoViewerGeometry.calculateDoubleTapScale(2.5f))
    }

    @Test fun bidirectionalDistanceDismissUsesProductionDecision() {
        assertTrue(PhotoViewerGeometry.shouldDismiss(300f))
        assertTrue(PhotoViewerGeometry.shouldDismiss(-300f))
        assertFalse(PhotoViewerGeometry.shouldDismiss(299f))
        assertFalse(PhotoViewerGeometry.shouldDismiss(-299f))
    }

    @Test fun productionDismissGateOnlyStartsOnce() {
        val gate = PhotoViewerDismissGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        assertFalse(gate.tryStart())
    }

    @Test fun directionScalePagerAndPinchArbitrationUsesProductionState() {
        fun pending() = PhotoViewerGestureState().apply { start() }

        assertEquals(PhotoViewerGestureMode.HORIZONTAL,
            pending().resolveSingleDrag(Offset(100f, 10f), 8f, 1f, false))
        assertEquals(PhotoViewerGestureMode.HORIZONTAL,
            pending().resolveSingleDrag(Offset(100f, 80f), 8f, 1f, false))
        assertEquals(PhotoViewerGestureMode.DISMISS,
            pending().resolveSingleDrag(Offset(20f, -100f), 8f, 1f, false))

        val pager = pending()
        assertEquals(PhotoViewerGestureMode.HORIZONTAL,
            pager.resolveSingleDrag(Offset(0f, 100f), 8f, 1f, true))
        assertFalse(pager.canDismiss(1f, true))

        val zoomed = pending()
        assertEquals(PhotoViewerGestureMode.PAN,
            zoomed.resolveSingleDrag(Offset(0f, 100f), 8f, 2f, false))
        assertFalse(zoomed.canDismiss(2f, false))

        val pinch = pending()
        assertEquals(PhotoViewerGestureMode.DISMISS,
            pinch.resolveSingleDrag(Offset(0f, 100f), 8f, 1f, false))
        pinch.updateDismissOffset(100f)
        assertEquals(100f, pinch.dismissOffset)
        assertEquals(PhotoViewerGestureMode.PINCH, pinch.updatePointerCount(2, 1f))
        assertEquals(0f, pinch.dismissOffset)
        assertFalse(pinch.canDismiss(1f, false))
        assertEquals(PhotoViewerGestureMode.PINCH_END_WAIT, pinch.updatePointerCount(1, 1f))
        assertFalse(pinch.canDismiss(1f, false))

        val zoomedPinch = pending()
        zoomedPinch.updatePointerCount(2, 1f)
        assertEquals(PhotoViewerGestureMode.PAN, zoomedPinch.updatePointerCount(1, 2f))
    }

    @Test fun offsetClampReactsToResizeAndBaseScale() {
        val initial = PhotoViewerGeometry.clampOffset(
            Offset(900f, 500f), 2f, 1000f, 1000f, 1000f, 1000f
        )
        assertEquals(500f, initial.x)
        assertEquals(500f, initial.y)

        val narrower = PhotoViewerGeometry.clampOffset(
            initial, 2f, 700f, 1000f, 700f, 700f
        )
        assertEquals(350f, narrower.x)
        assertEquals(200f, narrower.y)

        val rotated = PhotoViewerGeometry.clampOffset(
            Offset(800f, 800f), 2f, 1600f, 800f, 800f, 800f
        )
        assertEquals(0f, rotated.x)
        assertEquals(400f, rotated.y)
        assertEquals(Offset.Zero, PhotoViewerGeometry.clampOffset(
            Offset(10f, 10f), 1f, 1000f, 1000f, 1000f, 1000f
        ))
    }

    @Test fun masterAndThumbnailResolversAreStrictlySeparated() {
        val filesDir = temporaryFolder.newFolder("files")
        val master = createFile(filesDir, "media/master/photo.jpg")
        val thumbnail = createFile(filesDir, "media/thumbnail/photo.jpg")

        assertEquals(master.canonicalFile,
            resolveSafeMediaFile(filesDir, "media/master/photo.jpg", SafeMediaRoot.MASTER))
        assertEquals(thumbnail.canonicalFile,
            resolveSafeMediaFile(filesDir, "media/thumbnail/photo.jpg", SafeMediaRoot.THUMBNAIL))
        assertNull(resolveSafeMediaFile(filesDir, "media/thumbnail/photo.jpg", SafeMediaRoot.MASTER))
        assertNull(resolveSafeMediaFile(filesDir, "media/master/photo.jpg", SafeMediaRoot.THUMBNAIL))
    }

    @Test fun unsafeAndMissingPathsAreRejectedByProductionResolver() {
        val filesDir = temporaryFolder.newFolder("files")
        createFile(filesDir, "media/master_evil/photo.jpg")
        File(filesDir, "media/master/directory.jpg").mkdirs()

        listOf(
            "", "../photo.jpg", "media/master/../thumbnail/photo.jpg",
            "/absolute/photo.jpg", "file:///photo.jpg", "content://photo/1",
            "http://example/photo.jpg", "https://example/photo.jpg",
            "android.resource://package/photo", "media/master_evil/photo.jpg",
            "media/master2/photo.jpg", "media/master/missing.jpg",
            "media/master/directory.jpg"
        ).forEach { path ->
            assertNull(path, resolveSafeMediaFile(filesDir, path, SafeMediaRoot.MASTER))
        }
    }

    @Test fun canonicalSymlinkEscapeIsRejectedWhenSupported() {
        val filesDir = temporaryFolder.newFolder("files")
        val root = File(filesDir, "media/master").apply { mkdirs() }
        val outside = temporaryFolder.newFile("outside.jpg")
        val link = File(root, "linked.jpg")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertNull(resolveSafeMediaFile(filesDir, "media/master/linked.jpg", SafeMediaRoot.MASTER))
        } catch (_: UnsupportedOperationException) {
            // The canonical boundary is covered where this test runtime supports symlinks.
        } catch (_: java.nio.file.FileSystemException) {
            // Windows may deny symlink creation without developer mode.
        }
    }

    private fun createFile(root: File, relativePath: String): File =
        File(root, relativePath).apply {
            parentFile?.mkdirs()
            writeText("fixture")
        }
}
