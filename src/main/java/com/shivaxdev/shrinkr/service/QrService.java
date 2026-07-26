package com.shivaxdev.shrinkr.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
public class QrService {

    @Value("${app.qr.size}")
    private int size;   

    private static final int QR_COLOR    = 0xFF1a1a2e;  
    private static final int QR_BG_COLOR = 0xFFFFFFFF;  

    public byte[] generateQrPng(String shortUrl) {
        try {
            BitMatrix bitMatrix = encodeUrl(shortUrl);
            BufferedImage qrImage = renderQrImage(bitMatrix);
            return toPngBytes(qrImage);

        } catch (WriterException | IOException e) {
            log.error("QR generation failed for url={} reason={}", shortUrl, e.getMessage());
            throw new RuntimeException("Failed to generate QR code", e);
        }
    }

    private BitMatrix encodeUrl(String url) throws WriterException {
        Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN, 1    
        );
        return new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints);
    }

    private BufferedImage renderQrImage(BitMatrix bitMatrix) {
        int width  = bitMatrix.getWidth();
        int height = bitMatrix.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, bitMatrix.get(x, y) ? QR_COLOR : QR_BG_COLOR);
            }
        }
        return image;
    }

    private byte[] toPngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", outputStream);
        return outputStream.toByteArray();
    }
}
