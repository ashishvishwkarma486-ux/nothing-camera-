package com.nogy.autoenhance

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions

/** A face rectangle, as fractions (0..1) of the image width/height. */
class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Person mask: 255 = person, 0 = background. */
class PersonMask(val w: Int, val h: Int, val data: ByteArray)

/** Free, offline, on-phone AI (Google ML Kit). Never sends anything to the internet. */
object OnDevice {

    fun faces(thumb: Bitmap): List<FaceBox> {
        return try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.06f)
                .build()
            val detector = FaceDetection.getClient(options)
            val found = Tasks.await(detector.process(InputImage.fromBitmap(thumb, 0)))
            detector.close()
            val tw = thumb.width.toFloat()
            val th = thumb.height.toFloat()
            found.map {
                val b = it.boundingBox
                FaceBox(b.left / tw, b.top / th, b.right / tw, b.bottom / th)
            }
        } catch (t: Throwable) {
            Log.w("AutoEnhance", "Face detection failed: $t")
            emptyList()
        }
    }

    fun personMask(thumb: Bitmap): PersonMask? {
        return try {
            val options = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()
            val segmenter = Segmentation.getClient(options)
            val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(thumb, 0)))
            segmenter.close()
            val mw = mask.width
            val mh = mask.height
            val buf = mask.buffer
            buf.rewind()
            val data = ByteArray(mw * mh)
            for (i in 0 until mw * mh) {
                data[i] = (buf.getFloat().coerceIn(0f, 1f) * 255f).toInt().toByte()
            }
            PersonMask(mw, mh, data)
        } catch (t: Throwable) {
            Log.w("AutoEnhance", "Person segmentation failed: $t")
            null
        }
    }
}
