package dev.nightjar.rules;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable char trie over normalised numbers holding exact entries and prefix entries. Lookups are
 * allocation-free (sorted child arrays + binary search), which a {@code HashSet<String>} cannot offer without building
 * a key string per message. Keys are ASCII case-folded.
 */
final class NumberTrie {
    static final NumberTrie EMPTY = new NumberTrie(List.of(), List.of());

    private static final class Node {
        char[] keys = new char[0];
        Node[] kids = new Node[0];
        boolean exact;
        boolean prefix;

        Node child(char c) {
            int i = Arrays.binarySearch(keys, c);
            return i >= 0 ? kids[i] : null;
        }

        Node add(char c) {
            int i = Arrays.binarySearch(keys, c);
            if (i >= 0) return kids[i];
            int at = -i - 1;
            char[] nk = new char[keys.length + 1];
            Node[] nn = new Node[kids.length + 1];
            System.arraycopy(keys, 0, nk, 0, at);
            System.arraycopy(kids, 0, nn, 0, at);
            nk[at] = c;
            nn[at] = new Node();
            System.arraycopy(keys, at, nk, at + 1, keys.length - at);
            System.arraycopy(kids, at, nn, at + 1, kids.length - at);
            keys = nk;
            kids = nn;
            return nn[at];
        }
    }

    private final Node root = new Node();
    private final int size;

    /** Entries are raw values; they are normalised here. Blank entries are ignored. */
    NumberTrie(List<String> exact, List<String> prefixes) {
        int count = 0;
        for (String s : exact) if (insert(s, false)) count++;
        for (String s : prefixes) if (insert(s, true)) count++;
        size = count;
    }

    private boolean insert(String raw, boolean asPrefix) {
        if (raw == null) return false;
        String norm = Numbers.normalize(raw);
        if (norm.isEmpty()) return false;
        Node n = root;
        for (int i = 0; i < norm.length(); i++) n = n.add(Numbers.fold(norm.charAt(i)));
        if (asPrefix) n.prefix = true;
        else n.exact = true;
        return true;
    }

    int size() {
        return size;
    }

    /** True when the normalised number {@code buf[0..len)} equals an exact entry or starts with a prefix entry. */
    boolean contains(char[] buf, int len) {
        if (len <= 0) return false;
        Node n = root;
        for (int i = 0; i < len; i++) {
            n = n.child(Numbers.fold(buf[i]));
            if (n == null) return false;
            if (n.prefix) return true;
        }
        return n.exact;
    }
}
