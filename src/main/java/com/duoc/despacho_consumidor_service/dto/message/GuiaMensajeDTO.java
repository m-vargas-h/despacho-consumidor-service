package com.duoc.despacho_consumidor_service.dto.message;

import com.duoc.despacho_consumidor_service.enums.EstadoGuia;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * Debe mantenerse sincronizado campo a campo con GuiaMensajeDTO de
 * despacho-productor-service (viajan como JSON vía Jackson2JsonMessageConverter).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GuiaMensajeDTO implements Serializable {

    private String numeroGuia;
    private String transportista;
    private LocalDate fecha;
    private String direccionOrigen;
    private String direccionDestino;
    private String descripcionCarga;
    private EstadoGuia estado;
}
