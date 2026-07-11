# despacho-consumidor-service

Microservicio consumidor del sistema de Gestión de Pedidos y Guías de Despacho.

Consume los mensajes de creación de guías publicados por `despacho-productor-service`, genera el documento PDF, lo sube a S3, y guarda el registro definitivo en la base de datos compartida.

## Rol dentro de la arquitectura

Este servicio **no expone endpoints REST** ni recibe tráfico del API Gateway/Postman directamente — su única entrada es el mensaje que llega por RabbitMQ. Es intencional: separa la responsabilidad de "recibir y validar peticiones de clientes" (productor) de "procesar trabajo pesado de forma asíncrona" (consumidor).

```
RabbitMQ (cola.guias)
        │
        ▼
GuiaConsumerListener (ack manual)
        │
        ▼
GuiaProcesamientoService
        │
        ├─► Genera el PDF (PDFBox)
        ├─► Sube el PDF a S3
        ├─► Guarda la guía en guias_despacho (tabla canónica, compartida con el productor)
        └─► Registra el evento en historial_procesamiento_guias (tabla de auditoría)
```

## Manejo de errores: Dead Letter Queue

El listener usa **acknowledgment manual** (no automático), replicando el patrón visto en clases:

```java
try {
    // procesar mensaje...
    canal.basicAck(deliveryTag, false);
} catch (Exception e) {
    canal.basicNack(deliveryTag, false, false);
}
```

El `nack` con `requeue=false` es lo que activa el **Dead Letter Exchange** configurado a nivel de broker en la cola `cola.guias` (no hay código que publique manualmente en la DLQ): RabbitMQ reenruta automáticamente el mensaje fallido hacia `cola.guias.dlq`, que **no tiene consumidor propio** — los mensajes quedan ahí solo para revisión posterior.

### Gatillo de prueba (`ERROR-`)

Si el `numeroGuia` del mensaje comienza con el prefijo `ERROR-` (configurable vía `app.rabbitmq.test-fail-prefix`), el listener fuerza una excepción a propósito, sin tocar S3 ni la base de datos. Permite demostrar el flujo de la DLQ de forma controlada y repetible — se activa desde el productor con el campo `forzarError: true` en `POST /api/guias`.

## Idempotencia

Antes de procesar, el servicio verifica si ya existe una guía con ese `numeroGuia` en la base de datos. Si RabbitMQ llegara a reentregar un mensaje ya procesado (reintentos, reconexiones), no se duplica el registro.

## Documento PDF generado

Se genera con PDFBox (sin dependencias externas de plantillas): encabezado con título, caja destacada con número de guía y estado, tabla con los datos de la guía (fecha, transportista, direcciones, descripción de carga), espacios de firma, y pie de página con fecha de generación.

## Base de datos compartida

Se conecta al mismo servidor H2 en modo TCP que `despacho-productor-service`. Es el único de los dos microservicios que **escribe** en ambas tablas:

- `guias_despacho` — crea el registro definitivo de cada guía procesada con éxito.
- `historial_procesamiento_guias` — tabla de auditoría, distinta de la anterior, que registra cada procesamiento exitoso (número de guía, resultado, detalle, fecha).

## Mensajería (RabbitMQ)

- Escucha `cola.guias` (`@RabbitListener`, ack manual).
- La topología (exchange, colas, bindings, Dead Letter Exchange) se declara en `RabbitMQConfig`, de forma **idéntica** a la del productor — ambos microservicios declaran los mismos beans, así que cualquiera de los dos que arranque primero crea la infraestructura en RabbitMQ (declaración idempotente).
- El `Jackson2JsonMessageConverter` incluye un mapeo explícito de clases (`setIdClassMapping`) para poder deserializar el `GuiaMensajeDTO` publicado desde el paquete del productor (`com.duoc.despacho_productor_service...`) hacia la clase equivalente local de este proyecto — necesario porque son dos proyectos Maven independientes con paquetes distintos.

## Stack técnico

- Java 17, Spring Boot 3.5.x
- Spring Data JPA, Spring AMQP
- H2 Database (cliente, modo servidor)
- AWS SDK v2 (S3)
- Apache PDFBox
- Lombok

No incluye Spring Security ni OAuth2 Resource Server — no expone endpoints que requieran autenticación.

## Variables de entorno

| Variable | Obligatoria | Descripción |
|---|---|---|
| `PORT` | No (default `8081`) | Puerto del servidor (Tomcat embebido, sin endpoints activos) |
| `H2_SERVER_HOST` / `H2_SERVER_PORT` | No (default `localhost:1521`) | Host/puerto del servidor H2 compartido |
| `SPRING_RABBITMQ_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | No (default `localhost:5672`, `guest`/`guest`) | Conexión a RabbitMQ |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN` | Sí (para el flujo real) | Credenciales AWS (AWS Academy) |
| `AWS_S3_BUCKET_NAME` / `AWS_REGION` | Sí (para el flujo real) | Bucket S3 donde se suben los PDF |
| `EFS_TEMP_PATH` | No (default `target/efs/guias`) | Carpeta temporal donde se genera el PDF antes de subirlo a S3 |
| `APP_RABBITMQ_TEST_FAIL_PREFIX` | No (default `ERROR-`) | Prefijo del gatillo de prueba para forzar el envío a la DLQ |

Se recomienda usar un archivo `.env` en la raíz del proyecto (la dependencia `spring-dotenv` lo carga automáticamente). **Nunca commitear el `.env`**.

Sin credenciales AWS reales, el servicio arranca igual, pero cualquier guía que intente procesar fallará al subir a S3 y terminará en `cola.guias.dlq` — comportamiento esperado, útil para probar la mecánica de la DLQ sin depender de AWS.

## Cómo correr en local

Requiere que la infraestructura compartida esté levantada primero (clúster RabbitMQ + servidor H2).

```bash
mvn spring-boot:run
```

No hay endpoints HTTP que probar directamente — el trabajo se dispara al llegar un mensaje a `cola.guias` (publicado por el productor).

## Despliegue

CI/CD automático vía GitHub Actions (`.github/workflows/deploy-consumidor.yaml`): en cada push a `main`, compila con Maven, construye la imagen Docker, la publica en Docker Hub, y la despliega por SSH en la EC2 de microservicios (puerto `8081`), montando el volumen `/home/ec2-user/efs/guias` como carpeta temporal para los PDF.

## Estructura del proyecto

```
src/main/java/com/duoc/despacho_consumidor_service/
├── config/          # RabbitMQ, S3
├── dto/message/     # Contrato del mensaje (debe coincidir campo a campo con el del productor)
├── entity/          # GuiaDespacho (canónica), HistorialProcesamientoGuia (auditoría)
├── enums/           # EstadoGuia
├── messaging/       # GuiaConsumerListener — ack manual, gatillo de prueba
├── repository/      # Repositorios JPA
└── service/         # Generación de PDF, subida a S3, persistencia
```

