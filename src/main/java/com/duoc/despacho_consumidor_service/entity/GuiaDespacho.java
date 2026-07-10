package com.duoc.despacho_consumidor_service.entity;

import com.duoc.despacho_consumidor_service.enums.EstadoGuia;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

/**
 * Tabla canónica de guías de despacho, COMPARTIDA con despacho-productor-service
 * (misma base H2 en modo servidor). Este microservicio (consumidor) es el
 * ÚNICO que inserta filas nuevas aquí, al procesar el mensaje de creación
 * recibido desde la cola principal.
 */
@Data
@Entity
@Table(name = "guias_despacho")
public class GuiaDespacho {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String numeroGuia;

    @Column(nullable = false)
    private String transportista;

    @Column(nullable = false)
    private LocalDate fecha;

    @Column(nullable = false)
    private String direccionOrigen;

    @Column(nullable = false)
    private String direccionDestino;

    private String descripcionCarga;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoGuia estado;

    private String rutaEfs; // path temporal en el EFS de este microservicio
    private String rutaS3;  // key del objeto en S3
}
