package com.telerad.config;

import com.telerad.config.TeleradProperties.Rabbit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange teleradExchange(TeleradProperties props) {
        return new TopicExchange(props.getRabbit().getExchange(), true, false);
    }

    @Bean
    public Queue ingestQueue(TeleradProperties props) {
        return QueueBuilder.durable(props.getRabbit().getIngestQueue()).build();
    }

    @Bean
    public Binding ingestBinding(Queue ingestQueue, TopicExchange teleradExchange) {
        return BindingBuilder.bind(ingestQueue).to(teleradExchange).with("study.ingest");
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
