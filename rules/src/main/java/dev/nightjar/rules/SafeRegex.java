package dev.nightjar.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Linear-time regular-expression matcher for originator rules.
 *
 * <p>Why not {@code java.util.regex}: on Android, {@code Matcher} hands {@code input.toString()} to ICU, so a counting
 * {@code CharSequence} wrapper never sees the reads and cannot time-box a catastrophic pattern. This class compiles a
 * safe subset to a small NFA program and runs a Pike VM (no backtracking): cost is O(input × program), and a step
 * budget aborts anything larger (no match, reported to the caller).
 *
 * <p>Supported: literals, {@code .}, {@code [...]} with ranges and {@code ^} negation, {@code \d \D \w \W \s \S},
 * escaped punctuation, {@code ^ $}, groups {@code (...)} and {@code (?:...)}, {@code |}, {@code * + ?} and {@code
 * {n} {n,} {n,m}} (lazy suffix {@code ?} accepted). Not supported (syntax error): backreferences, lookaround, inline
 * flags, possessive quantifiers, Unicode classes. Semantics: {@code find()} (unanchored unless {@code ^}/{@code $}),
 * ASCII case-insensitive.
 *
 * <p>Thread: any. Immutable after compile. One per-thread scratch ({@link ThreadLocal}) serves every compiled pattern
 * and grows to the largest program; a rules update leaves no stale scratch behind. Allocation-free after a thread's
 * first call with its largest program.
 */
final class SafeRegex {
    /** Maximum pattern length. */
    static final int MAX_PATTERN = 200;

    /** Maximum compiled program size (bounds {@code {n,m}} expansion). */
    static final int MAX_PROGRAM = 2000;

    /**
     * Maximum number of AST node visits while the program is emitted. A repeated group that emits no instruction
     * ({@code (){1000}}) does not count against {@link #MAX_PROGRAM}, so nested ones ({@code ((){1000}){1000}...})
     * would otherwise take hours to compile. A pattern of 200 characters whose groups emit instructions stays far
     * below this limit (at most about 200 000 visits: nesting depth times {@link #MAX_PROGRAM}).
     */
    static final int MAX_EMIT_VISITS = 1_000_000;

    /** VM step budget per match; above it the match aborts (≈ well under 1 ms on a phone). */
    static final int STEP_BUDGET = 50_000;

    /**
     * VM step budget for all sender patterns of one message together. Each pattern also keeps {@link #STEP_BUDGET}, so
     * a very large rule set cannot slow one message down by more than this (≈ 10 to 20 ms on a phone).
     */
    static final int MESSAGE_STEP_BUDGET = 1_000_000;

    static final int MATCH = 1;
    static final int NO_MATCH = 0;
    static final int ABORTED = -1;

    /** Pattern rejected by {@link #compile}. */
    static final class SyntaxException extends Exception {
        private static final long serialVersionUID = 1L;
        final boolean tooLong;

        SyntaxException(String msg, boolean tooLong) {
            super(msg);
            this.tooLong = tooLong;
        }
    }

    // ------------------------------------------------------------ program
    private static final int OP_CHAR = 0;
    private static final int OP_ANY = 1;
    private static final int OP_CLASS = 2;
    private static final int OP_SPLIT = 3;
    private static final int OP_JMP = 4;
    private static final int OP_BOL = 5;
    private static final int OP_EOL = 6;
    private static final int OP_MATCH = 7;

    private static final ThreadLocal<Vm> SCRATCH = new ThreadLocal<>();

    private final int[] op;
    private final int[] x;
    private final int[] y;
    private final char[][] classRanges;
    private final boolean[] classNeg;

    private SafeRegex(int[] op, int[] x, int[] y, char[][] ranges, boolean[] neg) {
        this.op = op;
        this.x = x;
        this.y = y;
        this.classRanges = ranges;
        this.classNeg = neg;
    }

    static SafeRegex compile(String pattern) throws SyntaxException {
        if (pattern == null || pattern.isEmpty()) throw new SyntaxException("empty", false);
        if (pattern.length() > MAX_PATTERN) throw new SyntaxException("too long", true);
        Node ast = new Parser(pattern).parseAll();
        Emitter e = new Emitter();
        e.emit(ast);
        e.add(OP_MATCH, 0, 0);
        return new SafeRegex(e.ops(), e.xs(), e.ys(), e.ranges(), e.negs());
    }

    // ------------------------------------------------------------ matching

    private static final class Vm {
        final int[] cDense;
        final int[] cSparse;
        final int[] nDense;
        final int[] nSparse;
        final int[] stack;
        int[] curD;
        int[] curS;
        int[] nxtD;
        int[] nxtS;
        int cSize;
        int nSize;
        int steps;
        int limit;

        Vm(int n) {
            cDense = new int[n];
            cSparse = new int[n];
            nDense = new int[n];
            nSparse = new int[n];
            stack = new int[2 * n + 2];
        }
    }

    /** The steps of the last {@link #find} call on this thread (it can exceed the budget by one). */
    static int lastSteps() {
        Vm v = SCRATCH.get();
        return v == null ? 0 : v.steps;
    }

    /** Returns {@link #MATCH}, {@link #NO_MATCH} or {@link #ABORTED} (budget exceeded, treat as no match). */
    int find(CharSequence s) {
        return find(s, STEP_BUDGET);
    }

    /**
     * As {@link #find(CharSequence)}, but stops after {@code budget} steps (at most {@link #STEP_BUDGET}).
     * {@link #lastSteps()} then returns the steps this call used.
     */
    int find(CharSequence s, int budget) {
        Vm v = SCRATCH.get();
        if (v == null || v.cDense.length < op.length) {
            // A sparse set needs no clearing, so one scratch can serve programs of any size up to its own.
            v = new Vm(op.length);
            SCRATCH.set(v);
        }
        v.curD = v.cDense;
        v.curS = v.cSparse;
        v.nxtD = v.nDense;
        v.nxtS = v.nSparse;
        v.cSize = 0;
        v.steps = 0;
        v.limit = Math.min(budget, STEP_BUDGET);
        int n = s.length();
        for (int i = 0; ; i++) {
            // Unanchored search: start a new thread at every position.
            if (!addThread(v, true, 0, i, n)) return ABORTED;
            v.nSize = 0;
            char c = i < n ? Numbers.fold(s.charAt(i)) : 0;
            for (int k = 0; k < v.cSize; k++) {
                if (++v.steps > v.limit) return ABORTED;
                int pc = v.curD[k];
                switch (op[pc]) {
                    case OP_MATCH:
                        return MATCH;
                    case OP_CHAR:
                        if (i < n && c == x[pc] && !addThread(v, false, pc + 1, i + 1, n)) return ABORTED;
                        break;
                    case OP_ANY:
                        if (i < n && !addThread(v, false, pc + 1, i + 1, n)) return ABORTED;
                        break;
                    case OP_CLASS:
                        if (i < n && classMatch(x[pc], c) && !addThread(v, false, pc + 1, i + 1, n)) return ABORTED;
                        break;
                    default:
                        break;
                }
            }
            if (i >= n) return NO_MATCH;
            int[] t = v.curD;
            v.curD = v.nxtD;
            v.nxtD = t;
            t = v.curS;
            v.curS = v.nxtS;
            v.nxtS = t;
            v.cSize = v.nSize;
        }
    }

    /** Adds {@code pc} and its epsilon closure at position {@code i} to the current or next list. */
    private boolean addThread(Vm v, boolean current, int start, int i, int n) {
        int[] dense = current ? v.curD : v.nxtD;
        int[] sparse = current ? v.curS : v.nxtS;
        int size = current ? v.cSize : v.nSize;
        int[] st = v.stack;
        int sp = 0;
        st[sp++] = start;
        while (sp > 0) {
            if (++v.steps > v.limit) return false;
            int pc = st[--sp];
            int si = sparse[pc];
            if (si < size && dense[si] == pc) continue;
            sparse[pc] = size;
            dense[size++] = pc;
            switch (op[pc]) {
                case OP_JMP:
                    st[sp++] = x[pc];
                    break;
                case OP_SPLIT:
                    st[sp++] = y[pc];
                    st[sp++] = x[pc];
                    break;
                case OP_BOL:
                    if (i == 0) st[sp++] = pc + 1;
                    break;
                case OP_EOL:
                    if (i == n) st[sp++] = pc + 1;
                    break;
                default:
                    break;
            }
        }
        if (current) v.cSize = size;
        else v.nSize = size;
        return true;
    }

    private boolean classMatch(int cls, char folded) {
        char[] r = classRanges[cls];
        boolean in = inRanges(r, folded);
        if (!in && folded >= 'a' && folded <= 'z') in = inRanges(r, (char) (folded - ('a' - 'A')));
        return in != classNeg[cls];
    }

    private static boolean inRanges(char[] r, char c) {
        for (int i = 0; i < r.length; i += 2) {
            if (c >= r[i] && c <= r[i + 1]) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ AST

    private abstract static class Node {}

    private static final class Lit extends Node {
        final char c;

        Lit(char c) {
            this.c = c;
        }
    }

    private static final class Any extends Node {}

    private static final class Bol extends Node {}

    private static final class Eol extends Node {}

    private static final class Cls extends Node {
        final char[] ranges;
        final boolean neg;

        Cls(char[] ranges, boolean neg) {
            this.ranges = ranges;
            this.neg = neg;
        }
    }

    private static final class Cat extends Node {
        final List<Node> items;

        Cat(List<Node> items) {
            this.items = items;
        }
    }

    private static final class Alt extends Node {
        final List<Node> items;

        Alt(List<Node> items) {
            this.items = items;
        }
    }

    private static final class Rep extends Node {
        final Node body;
        final int min;
        final int max; // -1 = unbounded

        Rep(Node body, int min, int max) {
            this.body = body;
            this.min = min;
            this.max = max;
        }
    }

    // ------------------------------------------------------------ parser

    private static final char[] DIGIT = {'0', '9'};
    private static final char[] WORD = {'0', '9', 'A', 'Z', '_', '_', 'a', 'z'};
    /** {@code \s}: tab, line feed, VT, form feed, carriage return (9 to 13) and space. */
    private static final char[] SPACE = {'\t', '\r', ' ', ' '};

    private static final class Parser {
        final String p;
        int i;

        Parser(String p) {
            this.p = p;
        }

        SyntaxException err(String m) {
            return new SyntaxException(m + " at " + i, false);
        }

        Node parseAll() throws SyntaxException {
            Node n = parseAlt();
            if (i < p.length()) throw err("unexpected ')'");
            return n;
        }

        Node parseAlt() throws SyntaxException {
            List<Node> alts = new ArrayList<>();
            alts.add(parseCat());
            while (i < p.length() && p.charAt(i) == '|') {
                i++;
                alts.add(parseCat());
            }
            return alts.size() == 1 ? alts.get(0) : new Alt(alts);
        }

        Node parseCat() throws SyntaxException {
            List<Node> items = new ArrayList<>();
            while (i < p.length()) {
                char c = p.charAt(i);
                if (c == '|' || c == ')') break;
                Node atom = parseAtom();
                items.add(parseQuant(atom));
            }
            return new Cat(items);
        }

        Node parseQuant(Node atom) throws SyntaxException {
            if (i >= p.length()) return atom;
            char c = p.charAt(i);
            int min;
            int max;
            if (c == '*') {
                min = 0;
                max = -1;
                i++;
            } else if (c == '+') {
                min = 1;
                max = -1;
                i++;
            } else if (c == '?') {
                min = 0;
                max = 1;
                i++;
            } else if (c == '{') {
                int close = p.indexOf('}', i);
                if (close < 0) throw err("unclosed {");
                String body = p.substring(i + 1, close);
                // Digits and one comma only: Integer.parseInt alone would also take a sign ("{+1}").
                for (int k = 0; k < body.length(); k++) {
                    char b = body.charAt(k);
                    if (b != ',' && (b < '0' || b > '9')) throw err("bad {n,m}");
                }
                int comma = body.indexOf(',');
                try {
                    if (comma < 0) {
                        min = Integer.parseInt(body);
                        max = min;
                    } else {
                        min = Integer.parseInt(body.substring(0, comma));
                        String m = body.substring(comma + 1);
                        max = m.isEmpty() ? -1 : Integer.parseInt(m);
                    }
                } catch (NumberFormatException e) {
                    throw err("bad {n,m}");
                }
                if (min < 0 || (max >= 0 && max < min) || min > 1000 || max > 1000) throw err("bad {n,m}");
                i = close + 1;
            } else {
                return atom;
            }
            if (atom instanceof Bol || atom instanceof Eol) throw err("quantified anchor");
            if (i < p.length()) {
                char s = p.charAt(i);
                if (s == '?') i++; // lazy: same result for find()
                else if (s == '+') throw err("possessive quantifier");
            }
            if (i < p.length()) {
                char s = p.charAt(i);
                if (s == '*' || s == '+' || s == '?' || s == '{') throw err("nested quantifier");
            }
            return new Rep(atom, min, max);
        }

        Node parseAtom() throws SyntaxException {
            char c = p.charAt(i++);
            switch (c) {
                case '.':
                    return new Any();
                case '^':
                    return new Bol();
                case '$':
                    return new Eol();
                case '(':
                    if (i < p.length() && p.charAt(i) == '?') {
                        if (i + 1 < p.length() && p.charAt(i + 1) == ':') i += 2;
                        else throw err("unsupported group");
                    }
                    Node inner = parseAlt();
                    if (i >= p.length() || p.charAt(i) != ')') throw err("unclosed (");
                    i++;
                    return inner;
                case '[':
                    return parseClass();
                case '\\':
                    return parseEscape(false);
                case '*':
                case '+':
                case '?':
                case '{':
                    throw err("dangling quantifier");
                default:
                    return new Lit(Numbers.fold(c));
            }
        }

        Node parseEscape(boolean inClass) throws SyntaxException {
            if (i >= p.length()) throw err("trailing \\");
            char e = p.charAt(i++);
            switch (e) {
                case 'd':
                    return new Cls(DIGIT, false);
                case 'D':
                    if (inClass) throw err("negated shorthand in class");
                    return new Cls(DIGIT, true);
                case 'w':
                    return new Cls(WORD, false);
                case 'W':
                    if (inClass) throw err("negated shorthand in class");
                    return new Cls(WORD, true);
                case 's':
                    return new Cls(SPACE, false);
                case 'S':
                    if (inClass) throw err("negated shorthand in class");
                    return new Cls(SPACE, true);
                case 't':
                    return new Lit('\t');
                case 'n':
                    return new Lit('\n');
                case 'r':
                    return new Lit('\r');
                default:
                    if (Character.isLetterOrDigit(e)) throw err("unsupported escape \\" + e);
                    return new Lit(Numbers.fold(e));
            }
        }

        Node parseClass() throws SyntaxException {
            boolean neg = false;
            if (i < p.length() && p.charAt(i) == '^') {
                neg = true;
                i++;
            }
            StringBuilder r = new StringBuilder();
            boolean first = true;
            while (true) {
                if (i >= p.length()) throw err("unclosed [");
                char c = p.charAt(i);
                if (c == ']' && !first) {
                    i++;
                    break;
                }
                first = false;
                char lo;
                if (c == '\\') {
                    i++;
                    Node n = parseEscape(true);
                    if (n instanceof Cls) {
                        r.append(((Cls) n).ranges);
                        continue;
                    }
                    lo = ((Lit) n).c;
                } else {
                    if (c == '[') throw err("nested class");
                    lo = c;
                    i++;
                }
                char hi = lo;
                if (i + 1 < p.length() && p.charAt(i) == '-' && p.charAt(i + 1) != ']') {
                    i++;
                    char h = p.charAt(i);
                    if (h == '\\') {
                        i++;
                        Node n = parseEscape(true);
                        if (!(n instanceof Lit)) throw err("bad range");
                        hi = ((Lit) n).c;
                    } else {
                        hi = h;
                        i++;
                    }
                    if (hi < lo) throw err("bad range");
                }
                // Store both cases for letters so matching on folded (lower-case) input works.
                r.append(lo).append(hi);
                appendOtherCase(r, lo, hi);
            }
            char[] ranges = new char[r.length()];
            r.getChars(0, r.length(), ranges, 0);
            return new Cls(ranges, neg);
        }

        private static void appendOtherCase(StringBuilder r, char lo, char hi) {
            char a = (char) Math.max(lo, 'A');
            char b = (char) Math.min(hi, 'Z');
            if (a <= b) r.append((char) (a + 32)).append((char) (b + 32));
            a = (char) Math.max(lo, 'a');
            b = (char) Math.min(hi, 'z');
            if (a <= b) r.append((char) (a - 32)).append((char) (b - 32));
        }
    }

    // ------------------------------------------------------------ emitter

    private static final class Emitter {
        int[] op = new int[32];
        int[] x = new int[32];
        int[] y = new int[32];
        int size;
        int visits;
        final List<char[]> ranges = new ArrayList<>();
        final List<Boolean> negs = new ArrayList<>();

        int add(int o, int a, int b) throws SyntaxException {
            if (size >= MAX_PROGRAM) throw new SyntaxException("pattern too complex", false);
            if (size == op.length) {
                op = java.util.Arrays.copyOf(op, size * 2);
                x = java.util.Arrays.copyOf(x, size * 2);
                y = java.util.Arrays.copyOf(y, size * 2);
            }
            op[size] = o;
            x[size] = a;
            y[size] = b;
            return size++;
        }

        void emit(Node n) throws SyntaxException {
            if (++visits > MAX_EMIT_VISITS) throw new SyntaxException("pattern too complex", false);
            if (n instanceof Lit) {
                add(OP_CHAR, ((Lit) n).c, 0);
            } else if (n instanceof Any) {
                add(OP_ANY, 0, 0);
            } else if (n instanceof Bol) {
                add(OP_BOL, 0, 0);
            } else if (n instanceof Eol) {
                add(OP_EOL, 0, 0);
            } else if (n instanceof Cls) {
                ranges.add(((Cls) n).ranges);
                negs.add(((Cls) n).neg);
                add(OP_CLASS, ranges.size() - 1, 0);
            } else if (n instanceof Cat) {
                for (Node c : ((Cat) n).items) emit(c);
            } else if (n instanceof Alt) {
                List<Node> items = ((Alt) n).items;
                List<Integer> jumps = new ArrayList<>();
                for (int k = 0; k < items.size() - 1; k++) {
                    int split = add(OP_SPLIT, 0, 0);
                    x[split] = size;
                    emit(items.get(k));
                    jumps.add(add(OP_JMP, 0, 0));
                    y[split] = size;
                }
                emit(items.get(items.size() - 1));
                for (int j : jumps) x[j] = size;
            } else if (n instanceof Rep) {
                Rep r = (Rep) n;
                for (int k = 0; k < r.min; k++) emit(r.body);
                if (r.max < 0) {
                    int split = add(OP_SPLIT, 0, 0);
                    x[split] = size;
                    emit(r.body);
                    add(OP_JMP, split, 0);
                    y[split] = size;
                } else {
                    List<Integer> splits = new ArrayList<>();
                    for (int k = r.min; k < r.max; k++) {
                        int split = add(OP_SPLIT, 0, 0);
                        x[split] = size;
                        splits.add(split);
                        emit(r.body);
                    }
                    for (int s : splits) y[s] = size;
                }
            } else {
                throw new SyntaxException("internal", false);
            }
        }

        int[] ops() {
            return java.util.Arrays.copyOf(op, size);
        }

        int[] xs() {
            return java.util.Arrays.copyOf(x, size);
        }

        int[] ys() {
            return java.util.Arrays.copyOf(y, size);
        }

        char[][] ranges() {
            return ranges.toArray(new char[0][]);
        }

        boolean[] negs() {
            boolean[] b = new boolean[negs.size()];
            for (int k = 0; k < b.length; k++) b[k] = negs.get(k);
            return b;
        }
    }
}
