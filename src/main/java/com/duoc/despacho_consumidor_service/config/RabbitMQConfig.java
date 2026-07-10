package com.duoc.despacho_consumidor_service.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * IDÉNTICA a la de despacho-productor-service — misma topología declarada
 * en ambos microservicios.
 */
@Configuration
public class RabbitMQConfig {

    public static final String MAIN_EXCHANGE = "exchange.guias";
    public static final String DLX_EXCHANGE = "exchange.guias.dlx";
    public static final String MAIN_QUEUE = "cola.guias";
    public static final String DLQ_QUEUE = "cola.guias.dlq";
    public static final String ROUTING_KEY = "guias.routingkey";
    public static final String DLQ_ROUTING_KEY = "guias.dlq.routingkey";

    @Value("${spring.rabbitmq.host}")
    private String host;

    @Value("${spring.rabbitmq.port}")
    private int port;

    @Value("${spring.rabbitmq.username}")
    private String username;

    @Value("${spring.rabbitmq.password}")
    private String password;

    @Bean
    public CachingConnectionFactory connectionFactory() {
        CachingConnectionFactory factory = new CachingConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        return factory;
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("*");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(CachingConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }

    @Bean
    public DirectExchange mainExchange() {
        return new DirectExchange(MAIN_EXCHANGE);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue mainQueue() {
        return new Queue(MAIN_QUEUE, true, false, false,
                Map.of("x-dead-letter-exchange", DLX_EXCHANGE, "x-dead-letter-routing-key", DLQ_ROUTING_KEY));
    }

    @Bean
    public Queue dlqQueue() {
        return new Queue(DLQ_QUEUE, true);
    }

    @Bean
    public Binding mainBinding() {
        return BindingBuilder.bind(mainQueue()).to(mainExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(dlqQueue()).to(dlxExchange()).with(DLQ_ROUTING_KEY);
    }
}
