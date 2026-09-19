package segmentlog;

@FunctionalInterface
public interface Validator {
    boolean isValid(String payload);
}
