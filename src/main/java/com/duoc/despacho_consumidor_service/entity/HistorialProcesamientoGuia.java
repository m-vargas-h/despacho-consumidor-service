package com.duoc.despacho_consumidor_service.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Tabla de auditoría del procesamiento asíncrono — DISTINTA de "guias_despacho"
 * (cumple el requisito del enunciado de usar una tabla distinta a las de
 * sumativas anteriores). Este microservicio (consumidor) escribe una fila
 * acá cada vez que procesa exitosamente un mensaje. La DLQ no tiene
 * consumidor, así que los fallos NO se registran acá — quedan solo en la
 * cola de errores para revisión manual.
 */
@Data
@Entity
@Table(name = "historial_procesamiento_guias")
public class HistorialProcesamientoGuia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String numeroGuia;

    @Column(nullable = false)
    private String transportista;

    @Column(nullable = false)
    private String resultado;

    private String detalle;

    @Column(nullable = false)
    private LocalDateTime fechaProcesamiento;
}
