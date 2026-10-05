package dev.zfold.assistant;

/** Turns Silero frame probabilities into listening-state turn events. */
final class VoiceEndpointGate {
  enum Event {NONE, STARTED, UTTERANCE_ENDED, EMPTY_SPEECH_ENDED}

  interface Listener {
    void endpoint(Event event);
  }

  Listener listener() {return listener;}

  private final Listener listener;
  private volatile String candidate = "";
  private boolean started;
  private boolean finalRequested;
  private int activeFrames;
  private int quietFrames;
  private long startedAt;

  VoiceEndpointGate(Listener listener) {this.listener = listener;}

  synchronized void setCandidate(String value) {
    candidate = value == null ? "" : value.trim();
  }

  synchronized void reset() {
    candidate = "";
    started = false;
    finalRequested = false;
    activeFrames = 0;
    quietFrames = 0;
    startedAt = 0;
  }

  synchronized Event feed(float probability, double rms, long now) {
    boolean active = probability >= 0.80f && rms >= 0.009f;
    if (active) {
      activeFrames++;
      quietFrames = 0;
    } else {
      activeFrames = Math.max(0, activeFrames - 2);
      if (started) quietFrames++;
    }

    if (!started && activeFrames >= 6) {
      started = true;
      finalRequested = false;
      quietFrames = 0;
      startedAt = now;
      return Event.STARTED;
    }
    if (!started || finalRequested) return Event.NONE;

    // Android ASR needs a little time behind voice activity. A ~900 ms quiet
    // window keeps natural clause pauses usable while avoiding the old fixed
    // 1.6 s wait after every partial result.
    int quietLimit = hasText(candidate) ? (trailingOpen(candidate) ? 44 : 28) : 36;
    if (quietFrames < quietLimit) return Event.NONE;

    finalRequested = true;
    return hasText(candidate) ? Event.UTTERANCE_ENDED : Event.EMPTY_SPEECH_ENDED;
  }

  private static boolean hasText(String value) {
    return value.chars().anyMatch(Character::isLetterOrDigit);
  }

  private static boolean trailingOpen(String text) {
    String[] words = {"然后", "但是", "可是", "而且", "或者", "以及", "还有", "帮我", "把", "给", "请", "再", "并", "和"};
    for (String word : words) if (text.endsWith(word)) return true;
    return false;
  }

}
