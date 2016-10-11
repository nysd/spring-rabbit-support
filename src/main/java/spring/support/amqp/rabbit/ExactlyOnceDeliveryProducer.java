/**
 * 
 */
package spring.support.amqp.rabbit;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import spring.support.amqp.rabbit.repository.MutexRepository;

/**
 * @author yoshidan
 */
public class ExactlyOnceDeliveryProducer {

  public static final String MUTEX = "x-message-mutex";

  /**
   * 送信失敗メッセージの格納
   */
  private static final Logger UNDELIVERD_MESSAGE_LOGGER =
      LoggerFactory.getLogger("UNDELIVERD_MESSAGE");

  private static final Logger LOGGER = LoggerFactory.getLogger(ExactlyOnceDeliveryProducer.class);

  @Autowired
  private RabbitTemplate template;

  @Autowired
  private MutexRepository repository;

  @Autowired(required = false)
  private ErrorHandler handler = (mutex, message, t) -> {
    LOGGER.error(t.getMessage(), t);
    UNDELIVERD_MESSAGE_LOGGER.error(String.format("mutex=%s,message=%s", mutex, message));
  };

  /**
   * トランザクション完了後メッセージを送信する.
   * 
   * @param exchange
   * @param routingKey
   * @param payload
   */
  public String send(String exchange, String routingKey, Object payload) {
    return send(exchange, routingKey, payload, null, handler);
  }


  /**
   * トランザクション完了後メッセージを送信する.
   * 
   * @param exchange
   * @param routingKey
   * @param payload
   * @param properties
   */
  public String send(String exchange, String routingKey, Object payload,
      MessageProperties properties) {
    return send(exchange, routingKey, payload, properties, handler);
  }

  /**
   * トランザクション完了後メッセージを送信する.
   * 
   * @param exchange
   * @param routingKey
   * @param payload
   * @param handler
   */
  public String send(String exchange, String routingKey, Object payload,
      MessageProperties originProperties, ErrorHandler handler) {

    String mutex = repository.create();
    MessageProperties properties =
        originProperties == null ? new MessageProperties() : originProperties;
    properties.getHeaders().put(MUTEX, mutex);
    if (properties.getMessageId() == null) {
      properties.setMessageId(UUID.randomUUID().toString());
    }
    Message message = template.getMessageConverter().toMessage(payload, properties);

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      String savingMessage = new String(message.getBody());

      // Transaction内ではcommit後に送信
      TransactionSynchronizationManager
          .registerSynchronization(new TransactionSynchronizationAdapter() {
            public void afterCommit() {
              try {
                template.send(exchange, routingKey, message);
              } catch (Throwable t) {
                handler.accept(mutex, savingMessage, t);
              }
            }
          });
    } else {
      // Transactionが開始していなければ即時配信
      LOGGER.info("not transactional so send immediately");
      template.send(exchange, routingKey, message);
    }
    return mutex;

  }

  @FunctionalInterface
  public static interface ErrorHandler {
    void accept(String mutex, String savingMessage, Throwable t);
  }
}
