import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * What the unit tests of a CI job ran, read from the JUnit XML reports Gradle wrote: for every report folder the number of tests, failures, errors and skipped tests, and
 * every skipped test with the reason it gave. It is a program of the CI, run as {@code java java/ci/TestSummary.java [--allow-skip=text]... folder...} (a single source file,
 * the JDK's own XML parser, no shell and no extra tool, so it is the same on Linux and on Windows).
 *
 * <p>A skipped test is a test that did not check what it is named for. The job therefore fails (exit code 1) on a skipped test whose reason does not contain one of the
 * {@code --allow-skip} texts: an allowed skip is a fact about the runner that the workflow states in a comment (for example an account that can delete what an access control
 * list forbids it to delete), and any other skip is a test that silently stopped running. The job also fails when a folder holds no report or a report has a failure or an
 * error, so that the summary can not be read as a pass while the test task said otherwise.
 */
public final class TestSummary {

    private TestSummary() {
    }

    /** The counts of one folder of reports. */
    private static final class Counts {
        int tests;
        int skipped;
        int failures;
        int errors;
    }

    public static void main(String[] arguments) throws Exception {
        List<String> allowed = new ArrayList<>();
        List<Path> folders = new ArrayList<>();
        for (String argument : arguments) {
            if (argument.startsWith("--allow-skip=")) {
                allowed.add(argument.substring("--allow-skip=".length()));
            } else {
                folders.add(Path.of(argument));
            }
        }
        if (folders.isEmpty()) {
            System.err.println("usage: java TestSummary.java [--allow-skip=text]... folder...");
            System.exit(2);
        }
        Map<String, Counts> counts = new TreeMap<>();
        List<String> skippedTests = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        for (Path folder : folders) {
            read(folder, counts, skippedTests, unexpected, allowed);
        }
        int tests = 0;
        int skipped = 0;
        boolean failed = false;
        for (Map.Entry<String, Counts> entry : counts.entrySet()) {
            Counts folderCounts = entry.getValue();
            System.out.println(entry.getKey() + ": tests=" + folderCounts.tests + " skipped=" + folderCounts.skipped + " failures=" + folderCounts.failures + " errors=" + folderCounts.errors);
            tests += folderCounts.tests;
            skipped += folderCounts.skipped;
            failed |= folderCounts.failures > 0 || folderCounts.errors > 0;
        }
        System.out.println("total: tests=" + tests + " skipped=" + skipped);
        skippedTests.forEach(line -> System.out.println("skipped: " + line));
        if (counts.isEmpty()) {
            System.out.println("no test report was found in " + folders);
            failed = true;
        }
        if (!unexpected.isEmpty()) {
            unexpected.forEach(line -> System.out.println("UNEXPECTED SKIP: " + line));
            failed = true;
        }
        System.exit(failed ? 1 : 0);
    }

    private static void read(Path folder, Map<String, Counts> counts, List<String> skippedTests, List<String> unexpected, List<String> allowed) throws Exception {
        if (!Files.isDirectory(folder)) {
            return;
        }
        List<Path> reports;
        try (Stream<Path> files = Files.walk(folder)) {
            reports = files.filter(file -> file.getFileName().toString().startsWith("TEST-") && file.getFileName().toString().endsWith(".xml")).sorted().toList();
        }
        for (Path report : reports) {
            Element suite = parse(report);
            String where = folder.toString().replace('\\', '/') + " (" + report.getParent().getFileName() + ")";
            Counts folderCounts = counts.computeIfAbsent(where, key -> new Counts());
            folderCounts.tests += number(suite, "tests");
            folderCounts.skipped += number(suite, "skipped");
            folderCounts.failures += number(suite, "failures");
            folderCounts.errors += number(suite, "errors");
            NodeList cases = suite.getElementsByTagName("testcase");
            for (int index = 0; index < cases.getLength(); index++) {
                Element testCase = (Element) cases.item(index);
                NodeList skips = testCase.getElementsByTagName("skipped");
                if (skips.getLength() == 0) {
                    continue;
                }
                String reason = ((Element) skips.item(0)).getAttribute("message");
                String line = testCase.getAttribute("classname") + "." + testCase.getAttribute("name") + " - " + reason;
                skippedTests.add(line);
                if (allowed.stream().noneMatch(reason::contains)) {
                    unexpected.add(line);
                }
            }
        }
    }

    private static Element parse(Path report) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream in = Files.newInputStream(report)) {
            return builder.parse(in).getDocumentElement();
        } catch (IOException e) {
            throw new IOException("cannot read " + report, e);
        }
    }

    private static int number(Element suite, String attribute) {
        String value = suite.getAttribute(attribute);
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }
}
