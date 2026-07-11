package com.duoc.despacho_consumidor_service.service.impl;

import com.duoc.despacho_consumidor_service.dto.message.GuiaMensajeDTO;
import com.duoc.despacho_consumidor_service.entity.GuiaDespacho;
import com.duoc.despacho_consumidor_service.entity.HistorialProcesamientoGuia;
import com.duoc.despacho_consumidor_service.enums.EstadoGuia;
import com.duoc.despacho_consumidor_service.repository.GuiaDespachoRepository;
import com.duoc.despacho_consumidor_service.repository.HistorialProcesamientoGuiaRepository;
import com.duoc.despacho_consumidor_service.service.GuiaProcesamientoService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class GuiaProcesamientoServiceImpl implements GuiaProcesamientoService {

    private static final DateTimeFormatter DATE_FOLDER_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final GuiaDespachoRepository guiaDespachoRepository;
    private final HistorialProcesamientoGuiaRepository historialProcesamientoGuiaRepository;
    private final S3Client s3Client;
    private final Path storagePath;
    private final String bucketName;

    public GuiaProcesamientoServiceImpl(
            GuiaDespachoRepository guiaDespachoRepository,
            HistorialProcesamientoGuiaRepository historialProcesamientoGuiaRepository,
            S3Client s3Client,
            @Value("${storage.efs.path:target/efs/guias}") String storagePath,
            @Value("${aws.s3.bucket-name:}") String bucketName) {
        this.guiaDespachoRepository = guiaDespachoRepository;
        this.historialProcesamientoGuiaRepository = historialProcesamientoGuiaRepository;
        this.s3Client = s3Client;
        this.storagePath = Path.of(storagePath);
        this.bucketName = bucketName;
    }

    @Override
    @Transactional
    public void procesarGuia(GuiaMensajeDTO mensaje) throws IOException {
        if (guiaDespachoRepository.findByNumeroGuia(mensaje.getNumeroGuia()).isPresent()) {
            // Evita duplicar la guía si RabbitMQ llegara a reentregar el mismo mensaje.
            return;
        }

        GuiaDespacho guia = new GuiaDespacho();
        guia.setNumeroGuia(mensaje.getNumeroGuia());
        guia.setTransportista(mensaje.getTransportista());
        guia.setFecha(mensaje.getFecha());
        guia.setDireccionOrigen(mensaje.getDireccionOrigen());
        guia.setDireccionDestino(mensaje.getDireccionDestino());
        guia.setDescripcionCarga(mensaje.getDescripcionCarga());
        guia.setEstado(EstadoGuia.ENVIADO);

        Files.createDirectories(storagePath);
        Path archivoPdf = storagePath.resolve("guia-" + sanitizeFilePart(guia.getNumeroGuia()) + ".pdf");
        generatePdf(guia, archivoPdf);
        guia.setRutaEfs(archivoPdf.toString());

        String s3Key = buildS3Key(guia);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .contentType("application/pdf")
                        .build(),
                RequestBody.fromFile(archivoPdf));
        guia.setRutaS3(s3Key);
        guia.setEstado(EstadoGuia.ENVIADO);

        guiaDespachoRepository.save(guia);

        HistorialProcesamientoGuia historial = new HistorialProcesamientoGuia();
        historial.setNumeroGuia(guia.getNumeroGuia());
        historial.setTransportista(guia.getTransportista());
        historial.setResultado("EXITO");
        historial.setDetalle("Guía procesada, PDF generado y subido a S3 en " + s3Key);
        historial.setFechaProcesamiento(LocalDateTime.now());
        historialProcesamientoGuiaRepository.save(historial);
    }

    private String buildS3Key(GuiaDespacho guia) {
        String fechaFolder = guia.getFecha().format(DATE_FOLDER_FORMAT);
        String transportistaFolder = sanitizePathPart(guia.getTransportista());
        String fileName = "guia-" + sanitizeFilePart(guia.getNumeroGuia()) + ".pdf";
        return fechaFolder + "/" + transportistaFolder + "/" + fileName;
    }

    private void generatePdf(GuiaDespacho guia, Path filePath) throws IOException {
        Files.createDirectories(filePath.getParent());

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font fontItalic = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

            float pageWidth = page.getMediaBox().getWidth();
            float margin = 50;
            float contentWidth = pageWidth - (2 * margin);
            float cursorY = 780;

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {

                // ===== ENCABEZADO =====
                String titulo = "GUIA DE DESPACHO";
                float tituloWidth = fontBold.getStringWidth(titulo) / 1000 * 20;
                cs.beginText();
                cs.setFont(fontBold, 20);
                cs.newLineAtOffset((pageWidth - tituloWidth) / 2, cursorY);
                cs.showText(titulo);
                cs.endText();
                cursorY -= 18;

                String subtitulo = "Sistema de Gestion de Pedidos y Despacho";
                float subtituloWidth = fontItalic.getStringWidth(subtitulo) / 1000 * 10;
                cs.beginText();
                cs.setFont(fontItalic, 10);
                cs.newLineAtOffset((pageWidth - subtituloWidth) / 2, cursorY);
                cs.showText(subtitulo);
                cs.endText();
                cursorY -= 20;

                // Linea gruesa bajo el encabezado
                cs.setLineWidth(1.5f);
                cs.setStrokingColor(new PDColor(new float[]{0.1f, 0.1f, 0.1f}, PDDeviceRGB.INSTANCE));
                cs.moveTo(margin, cursorY);
                cs.lineTo(pageWidth - margin, cursorY);
                cs.stroke();
                cursorY -= 30;

                // ===== CAJA DESTACADA: NUMERO DE GUIA + ESTADO =====
                float boxHeight = 40;
                cs.setNonStrokingColor(new PDColor(new float[]{0.93f, 0.93f, 0.93f}, PDDeviceRGB.INSTANCE));
                cs.addRect(margin, cursorY - boxHeight, contentWidth, boxHeight);
                cs.fill();
                cs.setStrokingColor(new PDColor(new float[]{0.1f, 0.1f, 0.1f}, PDDeviceRGB.INSTANCE));
                cs.setLineWidth(0.8f);
                cs.addRect(margin, cursorY - boxHeight, contentWidth, boxHeight);
                cs.stroke();

                cs.beginText();
                cs.setFont(fontBold, 13);
                cs.setNonStrokingColor(new PDColor(new float[]{0f, 0f, 0f}, PDDeviceRGB.INSTANCE));
                cs.newLineAtOffset(margin + 15, cursorY - 25);
                cs.showText("N. Guia: " + sanitizePdfText(guia.getNumeroGuia()));
                cs.endText();

                String estadoTexto = "Estado: " + sanitizePdfText(guia.getEstado().toString());
                float estadoWidth = fontBold.getStringWidth(estadoTexto) / 1000 * 13;
                cs.beginText();
                cs.setFont(fontBold, 13);
                cs.newLineAtOffset(pageWidth - margin - 15 - estadoWidth, cursorY - 25);
                cs.showText(estadoTexto);
                cs.endText();

                cursorY -= (boxHeight + 30);

                // ===== TABLA DE DATOS =====
                String[][] filas = {
                    {"Fecha de emision", String.valueOf(guia.getFecha())},
                    {"Transportista", nullSafe(guia.getTransportista())},
                    {"Direccion de origen", nullSafe(guia.getDireccionOrigen())},
                    {"Direccion de destino", nullSafe(guia.getDireccionDestino())},
                    {"Descripcion de carga", nullSafe(guia.getDescripcionCarga())}
                };

                float labelColWidth = 160;
                float rowHeight = 28;
                float tableTop = cursorY;
                float tableBottom = cursorY - (rowHeight * filas.length);

                // Borde exterior de la tabla
                cs.setLineWidth(0.8f);
                cs.addRect(margin, tableBottom, contentWidth, tableTop - tableBottom);
                cs.stroke();

                // Linea vertical separando columna de etiqueta y valor
                cs.moveTo(margin + labelColWidth, tableTop);
                cs.lineTo(margin + labelColWidth, tableBottom);
                cs.stroke();

                float filaY = tableTop;
                for (int i = 0; i < filas.length; i++) {
                    if (i > 0) {
                        cs.moveTo(margin, filaY);
                        cs.lineTo(margin + contentWidth, filaY);
                        cs.stroke();
                    }

                    float textY = filaY - (rowHeight / 2f) - 4;

                    cs.beginText();
                    cs.setFont(fontBold, 10.5f);
                    cs.newLineAtOffset(margin + 10, textY);
                    cs.showText(sanitizePdfText(filas[i][0]));
                    cs.endText();

                    cs.beginText();
                    cs.setFont(fontRegular, 10.5f);
                    cs.newLineAtOffset(margin + labelColWidth + 10, textY);
                    cs.showText(sanitizePdfText(filas[i][1]));
                    cs.endText();

                    filaY -= rowHeight;
                }

                cursorY = tableBottom - 40;

                // ===== FIRMA / RECEPCION =====
                cs.setLineWidth(0.8f);
                cs.moveTo(margin, cursorY);
                cs.lineTo(margin + 180, cursorY);
                cs.stroke();
                cs.beginText();
                cs.setFont(fontRegular, 9);
                cs.newLineAtOffset(margin, cursorY - 12);
                cs.showText("Firma transportista");
                cs.endText();

                cs.moveTo(pageWidth - margin - 180, cursorY);
                cs.lineTo(pageWidth - margin, cursorY);
                cs.stroke();
                cs.beginText();
                cs.setFont(fontRegular, 9);
                cs.newLineAtOffset(pageWidth - margin - 180, cursorY - 12);
                cs.showText("Firma receptor");
                cs.endText();

                // ===== PIE DE PAGINA =====
                String pie = "Documento generado automaticamente el "
                        + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
                float pieWidth = fontItalic.getStringWidth(pie) / 1000 * 8;
                cs.beginText();
                cs.setFont(fontItalic, 8);
                cs.setNonStrokingColor(new PDColor(new float[]{0.4f, 0.4f, 0.4f}, PDDeviceRGB.INSTANCE));
                cs.newLineAtOffset((pageWidth - pieWidth) / 2, 40);
                cs.showText(pie);
                cs.endText();
            }

            document.save(filePath.toFile());
        }
    }

    private void writePdfLine(PDPageContentStream contentStream, String value) throws IOException {
        contentStream.showText(sanitizePdfText(value));
        contentStream.newLine();
    }

    private String sanitizePdfText(String value) {
        String normalized = Normalizer.normalize(nullSafe(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.chars()
                .mapToObj(character -> character >= 32 && character <= 126 ? String.valueOf((char) character) : "?")
                .collect(Collectors.joining());
    }

    private String sanitizeFilePart(String value) {
        return sanitizePathPart(value).replace('/', '_');
    }

    private String sanitizePathPart(String value) {
        return sanitizePdfText(value)
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
