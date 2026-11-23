package com.angadlobo.camera360

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves an equirectangular panorama as a JPEG tagged with Google Photo Sphere (GPano)
 * XMP metadata, so Google Photos, Facebook and most 360 viewers show it as a sphere.
 */
object PanoSaver {

    fun save(context: Context, pixels: IntArray, width: Int, height: Int): Uri {
        val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.toByteArray()
        }
        bmp.recycle()
        val tagged = injectXmp(jpeg, gpanoXmp(width, height))

        val name = "PANO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Camera360")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create image in gallery")
        resolver.openOutputStream(uri)?.use { it.write(tagged) } ?: error("Could not write image")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    fun gpanoXmp(width: Int, height: Int): String = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Camera360">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about=""
                xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
                GPano:UsePanoramaViewer="True"
                GPano:ProjectionType="equirectangular"
                GPano:CaptureSoftware="Camera360"
                GPano:PoseHeadingDegrees="0"
                GPano:FullPanoWidthPixels="$width"
                GPano:FullPanoHeightPixels="$height"
                GPano:CroppedAreaImageWidthPixels="$width"
                GPano:CroppedAreaImageHeightPixels="$height"
                GPano:CroppedAreaLeftPixels="0"
                GPano:CroppedAreaTopPixels="0"/>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    /** Inserts an XMP APP1 segment after SOI (and after the JFIF APP0 segment if present). */
    fun injectXmp(jpeg: ByteArray, xmp: String): ByteArray {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Not a JPEG" }
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val payload = xmp.toByteArray(Charsets.UTF_8)
        val segLen = 2 + header.size + payload.size
        require(segLen <= 0xFFFF) { "XMP too large" }

        var insertAt = 2
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            val app0Len = ((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)
            insertAt = 4 + app0Len
        }
        val out = ByteArrayOutputStream(jpeg.size + segLen + 2)
        out.write(jpeg, 0, insertAt)
        out.write(0xFF); out.write(0xE1)
        out.write((segLen shr 8) and 0xFF); out.write(segLen and 0xFF)
        out.write(header)
        out.write(payload)
        out.write(jpeg, insertAt, jpeg.size - insertAt)
        return out.toByteArray()
    }
}
