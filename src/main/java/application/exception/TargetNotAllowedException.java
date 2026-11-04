package application.exception;

/**
 * Целевой хост не входит в {@code app.target.allowed-hosts}.
 * <p>
 * Исполнитель считает такой шаг {@code FAILED}, а не уточнением: ответом пользователя запрет хоста не
 * снимается. Отдельный тип нужен, чтобы {@link application.service.HybridCaseRunner} не принял это
 * сообщение за ошибку конфигурации {@code api_base_url}.
 */
public class TargetNotAllowedException extends AssertionFailure {

    /**
     * Создаёт исключение с сообщением о запрещённом хосте.
     *
     * @param message описание запрещённого хоста
     */
    public TargetNotAllowedException(String message) {
        super(message);
    }
}
