package dev.nightjar.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.nightjar.app.domain.ClassRegistry;
import dev.nightjar.app.domain.SubtypeProfile;
import dev.nightjar.app.domain.text.TextKey;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Fate;
import dev.nightjar.contract.WireEnum;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Copy contract: every class and fate has its copy, names are unique, and
 * placeholders are well formed.
 *
 * <p>Two placeholder forms coexist by design (see the header of {@code values/strings.xml}):
 * {@code {name}} for text the Explainer fills, {@code %1$s} / {@code %1$d} for Android format
 * arguments in UI chrome. A string never mixes the two.
 *
 * <p>JVM only: the XML is parsed directly so the check runs without an Android device.
 */
class CopyCatalogueTest {

    /** Android format argument, with optional flags/width: {@code %1$s}, {@code %1$04X}, ... */
    private static final Pattern PERCENT = Pattern.compile("%(\\d+)\\$[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]");

    /** Explainer placeholder: {@code {app}}, {@code {count}}, ... */
    private static final Pattern BRACE = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

    /** Any stray brace, used to catch an unclosed placeholder. */
    private static final Pattern ANY_BRACE = Pattern.compile("[{}]");

    /**
     * {@code string/name} or {@code plurals/name} to the text of the {@code <string>} or of each {@code <item>}
     * under it. A string and a plurals resource can have the same name (Android keeps them apart).
     */
    private static final Map<String, List<String>> TEXT = new LinkedHashMap<>();

    private static final Set<String> NAMES = new TreeSet<>();

    @BeforeAll
    static void loadCatalogue() throws Exception {
        Path values = findValuesDir();
        Path plurals = values.resolve("plurals.xml");
        assertTrue(Files.isRegularFile(plurals), "missing " + plurals.toAbsolutePath());
        // Every values file with copy: strings.xml, plurals.xml and the strings_*.xml files of each feature.
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(values)) {
            list.filter(CopyCatalogueTest::isCopyFile).sorted().forEach(files::add);
        }
        for (Path file : files) {
            parse(file);
        }
    }

    private static boolean isCopyFile(Path file) {
        String name = file.getFileName().toString();
        return name.equals("strings.xml") || name.equals("plurals.xml")
                || (name.startsWith("strings_") && name.endsWith(".xml"));
    }

    /**
     * Gradle runs unit tests with the module directory as CWD, but the module may also be built
     * from the repository root; try both layouts instead of hard-coding one.
     */
    private static Path findValuesDir() {
        List<String> candidates =
                List.of(
                        "src/main/res/values",
                        "app/src/main/res/values",
                        "nightjar/app/src/main/res/values",
                        "../app/src/main/res/values");
        for (String candidate : candidates) {
            Path dir = Paths.get(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(dir.resolve("strings.xml"))) {
                return dir;
            }
        }
        throw new AssertionError(
                "strings.xml not found from "
                        + Paths.get("").toAbsolutePath()
                        + "; tried "
                        + candidates);
    }

    private static void parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        Document document;
        try {
            document = factory.newDocumentBuilder().parse(file.toFile());
        } catch (IOException | org.xml.sax.SAXException e) {
            throw new AssertionError("cannot parse " + file, e);
        }

        NodeList strings = document.getElementsByTagName("string");
        for (int i = 0; i < strings.getLength(); i++) {
            Element element = (Element) strings.item(i);
            String name = element.getAttribute("name");
            assertFalse(name.isEmpty(), file + " has a <string> without a name");
            add("string", name, List.of(element.getTextContent()), file);
        }

        NodeList plurals = document.getElementsByTagName("plurals");
        for (int i = 0; i < plurals.getLength(); i++) {
            Element plural = (Element) plurals.item(i);
            String name = plural.getAttribute("name");
            assertFalse(name.isEmpty(), file + " has a <plurals> without a name");
            List<String> items = new ArrayList<>();
            NodeList children = plural.getChildNodes();
            for (int c = 0; c < children.getLength(); c++) {
                Node child = children.item(c);
                if (child.getNodeType() == Node.ELEMENT_NODE && "item".equals(child.getNodeName())) {
                    items.add(child.getTextContent());
                }
            }
            assertFalse(items.isEmpty(), "plurals " + name + " has no items");
            add("plurals", name, items, file);
        }
    }

    private static void add(String kind, String name, List<String> texts, Path file) {
        String key = kind + "/" + name;
        if (TEXT.containsKey(key)) {
            fail("duplicate resource name: " + key + " (again in " + file + ")");
        }
        TEXT.put(key, texts);
        NAMES.add(name);
    }

    // ------------------------------------------------------------------ coverage

    /**
     * Each class has the title and the explanation that {@link ClassRegistry} names. The cell broadcast classes use
     * the {@code title_cb_*} and {@code exp_cb_*} copy of CbCategory, not a {@code title_cl_*} name.
     */
    @Test
    void everyClassHasTitleAndExplanation() {
        Set<String> classSuffixes = new TreeSet<>();
        List<String> missing = new ArrayList<>();
        for (ClassId id : ClassId.values()) {
            classSuffixes.add(suffix(id));
            TextKey title = ClassRegistry.INSTANCE.of(id).getTitleKey();
            TextKey explanation = ClassRegistry.INSTANCE.of(id).getExplanationKey();
            for (TextKey key : List.of(title, explanation)) {
                if (!NAMES.contains(key.getResource())) {
                    missing.add(id.wire() + ": " + key.getResource());
                }
            }
        }
        assertTrue(missing.isEmpty(), "missing class copy: " + missing);

        // Extra titles are allowed only where DESIGN splits a class (CB emergency alerts).
        classSuffixes.add("cb_emergency");
        // ...and a push whose header cannot be read, which is described as malformed rather than as "push (unknown)".
        classSuffixes.add("wap_unreadable");
        // ...and the data-port name for lists that have no port number to show.
        classSuffixes.add("data_port_generic");
        for (String name : NAMES) {
            if (name.startsWith("title_cl_")) {
                assertTrue(
                        classSuffixes.contains(name.substring("title_cl_".length())),
                        "title for an unknown class: " + name);
            } else if (name.startsWith("exp_cl_")) {
                assertTrue(
                        classSuffixes.contains(name.substring("exp_cl_".length())),
                        "explanation for an unknown class: " + name);
            }
        }
    }

    /** Each subtype row of the class taxonomy has its own title and explanation in strings.xml. */
    @Test
    void everySubtypeRowHasTitleAndExplanation() {
        List<String> missing = new ArrayList<>();
        int rows = 0;
        for (ClassId id : ClassId.values()) {
            for (SubtypeProfile row : ClassRegistry.INSTANCE.subtypes(id)) {
                rows++;
                for (TextKey key : List.of(row.getTitleKey(), row.getExplanationKey())) {
                    if (!NAMES.contains(key.getResource())) {
                        missing.add(id.wire() + " " + row.getCodes() + ": " + key.getResource());
                    }
                }
            }
        }
        assertTrue(rows >= 50, "subtype rows: " + rows);
        assertTrue(missing.isEmpty(), "missing subtype copy: " + missing);
    }

    @Test
    void everyFateHasCopy() {
        Set<String> fateSuffixes = new TreeSet<>();
        List<String> missing = new ArrayList<>();
        for (Fate fate : Fate.values()) {
            String suffix = suffix(fate);
            fateSuffixes.add(suffix);
            String name = "fate_txt_" + suffix;
            if (!NAMES.contains(name)) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "missing fate copy: " + missing);
        // A block with no rule behind it (a malformed push, a self-test message) has its own line.
        fateSuffixes.add("blocked_no_rule");
        // ...and a send from an app the user blocked from sending SMS.
        fateSuffixes.add("blocked_app");
        // ...and an OEM redirect that names no app (Explainer: FATE_TXT_OEM_REDIRECTED_NO_APP).
        fateSuffixes.add("oem_redirected_no_app");
        for (String name : NAMES) {
            if (name.startsWith("fate_txt_")) {
                assertTrue(
                        fateSuffixes.contains(name.substring("fate_txt_".length())),
                        "copy for an unknown fate: " + name);
            }
        }
    }

    /**
     * ResourceStrings reads each {@link TextKey} by name, and lint and the resource shrinker cannot see that read. So
     * each TextKey resource must match an entry of {@code tools:keep} in {@code res/raw/keep.xml}, and each entry
     * without a {@code *} must name a string that exists.
     */
    @Test
    void everyTextKeyIsKept() throws Exception {
        Path keepFile = findValuesDir().resolveSibling("raw").resolve("keep.xml");
        assertTrue(Files.isRegularFile(keepFile), "missing " + keepFile.toAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        Element root = factory.newDocumentBuilder().parse(keepFile.toFile()).getDocumentElement();
        List<Pattern> kept = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (String entry : root.getAttribute("tools:keep").split(",")) {
            assertTrue(entry.startsWith("@string/"), "keep entry is not a string: " + entry);
            String name = entry.substring("@string/".length());
            if (!name.contains("*") && !NAMES.contains(name)) {
                stale.add(name);
            }
            kept.add(Pattern.compile(Pattern.quote(name).replace("*", "\\E.*\\Q")));
        }
        assertTrue(stale.isEmpty(), "keep.xml names strings that do not exist: " + stale);
        List<String> missing = new ArrayList<>();
        for (TextKey key : TextKey.values()) {
            String name = key.getResource();
            if (kept.stream().noneMatch(pattern -> pattern.matcher(name).matches())) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "TextKey resources not in res/raw/keep.xml: " + missing);
    }

    /**
     * Every {@code R.string}, {@code R.plurals} and {@code @string}/{@code @plurals} reference in the app sources
     * exists in the catalogue. The catalogue holds only copy that the app uses, so this replaces the old
     * fixed list of string ids, many of which no screen shows.
     */
    @Test
    void everyStringTheAppUsesExists() throws IOException {
        Path src = findValuesDir().getParent().getParent().getParent();
        Pattern code = Pattern.compile("R\\.(string|plurals)\\.([A-Za-z0-9_]+)");
        Pattern xml = Pattern.compile("@(string|plurals)/([A-Za-z0-9_]+)");
        Set<String> missing = new TreeSet<>();
        int references = 0;
        for (String sourceSet : List.of("main", "debug")) {
            Path root = src.resolve(sourceSet);
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                // res/raw/keep.xml holds keep patterns, not references: everyTextKeyIsKept checks it.
                walk.filter(Files::isRegularFile)
                        .filter(f -> !f.endsWith(Paths.get("raw", "keep.xml")))
                        .filter(f -> f.toString().endsWith(".kt") || f.toString().endsWith(".java") || f.toString().endsWith(".xml"))
                        .forEach(files::add);
            }
            for (Path file : files) {
                String text = Files.readString(file);
                Matcher matcher = (file.toString().endsWith(".xml") ? xml : code).matcher(text);
                while (matcher.find()) {
                    references++;
                    String key = matcher.group(1) + "/" + matcher.group(2);
                    if (!TEXT.containsKey(key)) {
                        missing.add(key + " (" + src.relativize(file) + ")");
                    }
                }
            }
        }
        assertTrue(references > 500, "references found: " + references);
        assertTrue(NAMES.contains("app_name"), "missing app_name");
        assertTrue(missing.isEmpty(), "missing copy: " + missing);
    }

    // ------------------------------------------------------------------ hygiene

    @Test
    void namesAreUniqueAndPercentPlaceholdersAreContiguous() {
        for (Map.Entry<String, List<String>> entry : TEXT.entrySet()) {
            for (String text : entry.getValue()) {
                List<Integer> indices = new ArrayList<>();
                Matcher matcher = PERCENT.matcher(text);
                while (matcher.find()) {
                    indices.add(Integer.parseInt(matcher.group(1)));
                }
                indices.sort(Integer::compareTo);
                for (int i = 0; i < indices.size(); i++) {
                    assertEquals(
                            i + 1,
                            indices.get(i).intValue(),
                            entry.getKey() + ": format arguments must start at %1$ and be contiguous: " + text);
                }
                String withoutPlaceholders = PERCENT.matcher(text).replaceAll("");
                if (withoutPlaceholders.indexOf('%') >= 0) {
                    fail(entry.getKey() + ": stray '%' outside a format argument: " + text);
                }
            }
        }
    }

    @Test
    void bracePlaceholdersAreWellFormedAndNeverMixedWithFormatArguments() {
        for (Map.Entry<String, List<String>> entry : TEXT.entrySet()) {
            for (String text : entry.getValue()) {
                Matcher braces = BRACE.matcher(text);
                List<String> tokens = new ArrayList<>();
                while (braces.find()) {
                    tokens.add(braces.group(1));
                }
                String leftover = BRACE.matcher(text).replaceAll("");
                if (ANY_BRACE.matcher(leftover).find()) {
                    fail(entry.getKey() + ": unclosed or malformed placeholder: " + text);
                }
                if (!tokens.isEmpty() && PERCENT.matcher(text).find()) {
                    fail(entry.getKey() + ": mixes {name} and %1$ placeholders: " + text);
                }
                if (!tokens.isEmpty() && !text.contains("{" + tokens.get(0) + "}")) {
                    fail(entry.getKey() + ": placeholder without braces: " + text);
                }
            }
        }
    }

    @Test
    void apostrophesAreEscapedForAapt() {
        Pattern unescaped = Pattern.compile("(?<!\\\\)'");
        for (Map.Entry<String, List<String>> entry : TEXT.entrySet()) {
            for (String text : entry.getValue()) {
                assertFalse(
                        unescaped.matcher(text).find(),
                        entry.getKey() + ": apostrophe must be escaped as \\': " + text);
            }
        }
    }

    @Test
    void catalogueHasNoPlaceholderStringsAndIsLargeEnough() {
        for (String name : NAMES) {
            assertFalse(name.startsWith("placeholder_"), "leftover placeholder string: " + name);
        }
        assertTrue(NAMES.size() >= 250, "catalogue is too small: " + NAMES.size());
    }

    /** And the copy standard: the brand is written "NightJar" in all user text. */
    @Test
    void appNameAndCopyUseTheNightJarBrand() {
        List<String> text = TEXT.get("string/app_name");
        assertNotNull(text, "app_name is missing");
        assertEquals("NightJar", text.get(0));
        Pattern wrongBrand = Pattern.compile("\\bNightjar\\b|\\bNIGHTJAR\\b");
        for (Map.Entry<String, List<String>> entry : TEXT.entrySet()) {
            for (String copy : entry.getValue()) {
                assertFalse(
                        wrongBrand.matcher(copy).find(),
                        entry.getKey() + ": write the brand as NightJar: " + copy);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** {@code CL-WAP-CP} to {@code wap_cp}; {@code FT-NO-RECEIVER} to {@code no_receiver}. */
    private static String suffix(WireEnum value) {
        String wire = value.wire();
        int dash = wire.indexOf('-');
        assertTrue(dash > 0, "unexpected wire name: " + wire);
        return wire.substring(dash + 1).toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
