package skitrak.consumer;

// same fields as what SwipePublisher sends
final class SwipeMessage {
    String eventId;
    String resortID;
    String dayID;
    String skierID;
    String liftID;
    int vertical;
    int time;
    String timestamp;
    String serverId;
}