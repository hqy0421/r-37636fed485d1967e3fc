package technology.tabula.diff;

import java.util.Comparator;
import java.util.List;

/**
 * Lexicographic ordering of string lists, used to keep composite keys sorted
 * stably regardless of insertion order.
 */
public class ListLexicographicComparator implements Comparator<List<String>> {

    @Override
    public int compare(List<String> a, List<String> b) {
        int size = Math.min(a.size(), b.size());
        for (int i = 0; i < size; i++) {
            int cmp = a.get(i).compareTo(b.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.size(), b.size());
    }
}
