package pro.masterdoc.catalog

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

private const val PDF_IMAGE_WIDTH = 900
private const val PDF_IMAGE_HEIGHT = 1273
private const val QR_SIZE = 760

fun createAssetQrPdf(asset: Asset, qrUrl: String): ByteArray {
    require(asset.name.isNotBlank()) { "Asset name required" }

    val image = BufferedImage(PDF_IMAGE_WIDTH, PDF_IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val qr = QRCodeWriter().encode(qrUrl, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
        val qrLeft = (image.width - qr.width) / 2
        val qrTop = 90
        graphics.color = Color.BLACK
        for (y in 0 until qr.height) {
            for (x in 0 until qr.width) {
                if (qr[x, y]) graphics.fillRect(qrLeft + x, qrTop + y, 1, 1)
            }
        }

        graphics.font = fitFont(asset.name, 58, image.width - 100, graphics)
        val nameWidth = graphics.fontMetrics.stringWidth(asset.name)
        graphics.drawString(asset.name, (image.width - nameWidth) / 2, 970)

        asset.inventoryNo?.let { inventoryNo ->
            val label = "Инв. № $inventoryNo"
            graphics.font = fitFont(label, 36, image.width - 100, graphics)
            val labelWidth = graphics.fontMetrics.stringWidth(label)
            graphics.drawString(label, (image.width - labelWidth) / 2, 1040)
        }
    } finally {
        graphics.dispose()
    }

    return ByteArrayOutputStream().use { output ->
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val pdfImage = LosslessFactory.createFromImage(document, image)
            PDPageContentStream(document, page).use { content ->
                content.drawImage(pdfImage, 0f, 0f, page.mediaBox.width, page.mediaBox.height)
            }
            document.save(output)
        }
        output.toByteArray()
    }
}

private fun fitFont(
    text: String,
    initialSize: Int,
    maxWidth: Int,
    graphics: java.awt.Graphics2D,
): Font {
    var size = initialSize
    var font = Font(Font.SANS_SERIF, Font.BOLD, size)
    while (size > 20 && graphics.getFontMetrics(font).stringWidth(text) > maxWidth) {
        size -= 1
        font = font.deriveFont(size.toFloat())
    }
    return font
}
