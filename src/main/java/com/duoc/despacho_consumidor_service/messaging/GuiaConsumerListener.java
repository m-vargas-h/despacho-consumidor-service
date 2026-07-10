package com.duoc.despacho_consumidor_service.messaging;

import com.duoc.despacho_consumidor_service.config.RabbitMQConfig;
import com.duoc.despacho_consumidor_service.dto.message.GuiaMensajeDTO;
import com.duoc.despacho_consumidor_service.service.GuiaProcesamientoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Consumidor de la cola principal (cola.guias), con acknowledgment MANUAL —
 * mismo patrón mostrado en clase (msrabbitmq / ConsumirMensajeServiceImpl):
 *
 *   try { procesar(); channel.basicAck(...); }
 *   catch (Exception e) { channel.basicNack(..., requeue=false); }
 *
 * El nack con requeue=false es lo que dispara el Dead Letter Exchange
 * configurado en la cola (RabbitMQConfig), reenrutando el mensaje
 * automáticamente hacia cola.guias.dlq — que no tiene consumidor.
 */
@Component
public class GuiaConsumerListener {

    private static final Logger log = LoggerFactory.getLogger(GuiaConsumerListener.class);

    private final GuiaProcesamientoService guiaProcesamientoService;
    private final ObjectMapper objectMapper;

    // Gatillo de prueba para demostrar el flujo de la DLQ en el video: si el
    // numeroGuia empieza con este prefijo, se fuerza un error a propósito
    // (mismo recurso que usó el profesor con la palabra "error" en el body).
    @Value("${app.rabbitmq.test-fail-prefix:ERROR-}")
    private String testFailPrefix;

    public GuiaConsumerListener(GuiaProcesamientoService guiaProcesamientoService, ObjectMapper objectMapper) {
        this.guiaProcesamientoService = guiaProcesamientoService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(id = "listener-cola-guias", queues = RabbitMQConfig.MAIN_QUEUE, ackMode = "MANUAL")
    public void recibirGuia(Message mensaje, Channel canal) throws IOException {
        long deliveryTag = mensaje.getMessageProperties().getDeliveryTag();
        GuiaMensajeDTO guiaMensaje = null;

        try {
            guiaMensaje = objectMapper.readValue(mensaje.getBody(), GuiaMensajeDTO.class);
            log.info("Mensaje recibido en cola.guias: {}", guiaMensaje.getNumeroGuia());

            if (guiaMensaje.getNumeroGuia() != null && guiaMensaje.getNumeroGuia().startsWith(testFailPrefix)) {
                throw new RuntimeException("Error forzado con fines de demostración de la DLQ");
            }

            guiaProcesamientoService.procesarGuia(guiaMensaje);

            canal.basicAck(deliveryTag, false);
            log.info("Guía {} procesada y ack enviado", guiaMensaje.getNumeroGuia());
        } catch (Exception ex) {
            log.error("Error al procesar guía {}: {}. Se envía a la DLQ.",
                    guiaMensaje != null ? guiaMensaje.getNumeroGuia() : "desconocida", ex.getMessage());
            canal.basicNack(deliveryTag, false, false);
        }
    }
}
