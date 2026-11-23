package skitrak.consumer;

import java.util.ArrayList;
import java.util.List;

// top-N for one resort + day. only touched by that resort's consumer, except snapshot()
//
// works because totals only go up (vertical is always > 0):
// someone outside the board can only get in by beating the last entry,
// and someone on the board can only move up. so we never need to sort all skiers
final class TopN {

    static final class Entry {
        final String skierID;
        final long vertical;

        Entry(String skierID, long vertical) {
            this.skierID = skierID;
            this.vertical = vertical;
        }
    }

    private final int n;
    private final ArrayList<Entry> top = new ArrayList<>();   // sorted best first, size <= n

    // immutable copy for other threads (reporter now, websocket broadcast in step 4)
    private volatile List<Entry> snapshot = List.of();

    TopN(int n) {
        this.n = n;
    }

    // skierID's total just became newTotal. returns true if the board changed
    boolean update(String skierID, long newTotal) {
        int idx = indexOf(skierID);
        if (idx >= 0) {
            top.remove(idx);
        } else if (top.size() == n && !beats(newTotal, skierID, top.get(n - 1))) {
            return false;   // most swipes end here, O(n) check and nothing else
        }

        Entry e = new Entry(skierID, newTotal);
        int pos = 0;
        while (pos < top.size() && !beats(newTotal, skierID, top.get(pos))) {
            pos++;
        }
        top.add(pos, e);
        if (top.size() > n) {
            top.remove(n);
        }
        snapshot = List.copyOf(top);
        return true;
    }

    List<Entry> snapshot() {
        return snapshot;
    }

    private int indexOf(String skierID) {
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).skierID.equals(skierID)) {
                return i;
            }
        }
        return -1;
    }

    // higher vertical wins, ties go to the smaller skierID so the order is stable
    private static boolean beats(long vertical, String skierID, Entry other) {
        return vertical > other.vertical
                || (vertical == other.vertical && compareIds(skierID, other.skierID) < 0);
    }

    // numeric compare without parsing: ids are positive ints, so a shorter one is smaller.
    // plain string compare would put "45591" before "4615"
    private static int compareIds(String a, String b) {
        if (a.length() != b.length()) {
            return Integer.compare(a.length(), b.length());
        }
        return a.compareTo(b);
    }
}