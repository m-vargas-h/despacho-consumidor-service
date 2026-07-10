package com.duoc.despacho_consumidor_service.service;

import com.duoc.despacho_consumidor_service.dto.message.GuiaMensajeDTO;

public interface GuiaProcesamientoService {

    /**
     * Genera el PDF, lo sube a S3, guarda la guía en la tabla canónica y
     * deja registro en la tabla de auditoría. Lanza excepción si algo falla,
     * para que el listener decida hacer nack (y así activar el DLX).
     */
    void procesarGuia(GuiaMensajeDTO mensaje) throws Exception;
}
