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
        guia.setEstado(EstadoGuia.PENDIENTE);

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

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(font, 12);
                contentStream.setLeading(16f);
                contentStream.newLineAtOffset(50, 780);
                writePdfLine(contentStream, "Guia de despacho");
                writePdfLine(contentStream, "Numero guia: " + guia.getNumeroGuia());
                writePdfLine(contentStream, "Transportista: " + guia.getTransportista());
                writePdfLine(contentStream, "Fecha: " + guia.getFecha());
                writePdfLine(contentStream, "Origen: " + guia.getDireccionOrigen());
                writePdfLine(contentStream, "Destino: " + guia.getDireccionDestino());
                writePdfLine(contentStream, "Descripcion: " + nullSafe(guia.getDescripcionCarga()));
                writePdfLine(contentStream, "Estado: " + guia.getEstado());
                contentStream.endText();
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
