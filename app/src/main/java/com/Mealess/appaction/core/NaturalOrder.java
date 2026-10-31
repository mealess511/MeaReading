package com.Mealess.appaction.core;

import java.util.Comparator;

/** "page2" sorts before "page10" - used for comic page ordering. */
public final class NaturalOrder implements Comparator<String> {

    public static final NaturalOrder INSTANCE = new NaturalOrder();

    private NaturalOrder() {}

    @Override
    public int compare(String s1, String s2) {
        if (s1 == null) return s2 == null ? 0 : -1;
        if (s2 == null) return 1;
        int i = 0, j = 0;
        int len1 = s1.length(), len2 = s2.length();
        while (i < len1 && j < len2) {
            char c1 = s1.charAt(i);
            char c2 = s2.charAt(j);
            if (c1 >= '0' && c1 <= '9' && c2 >= '0' && c2 <= '9') {
                int si = i, sj = j;
                while (i < len1 && s1.charAt(i) >= '0' && s1.charAt(i) <= '9') i++;
                while (j < len2 && s2.charAt(j) >= '0' && s2.charAt(j) <= '9') j++;
                String n1 = trimZeros(s1.substring(si, i));
                String n2 = trimZeros(s2.substring(sj, j));
                if (n1.length() != n2.length()) return n1.length() - n2.length();
                int cmp = n1.compareTo(n2);
                if (cmp != 0) return cmp;
            } else {
                char l1 = Character.toLowerCase(c1);
                char l2 = Character.toLowerCase(c2);
                if (l1 != l2) return l1 - l2;
                i++;
                j++;
            }
        }
        int rest = (len1 - i) - (len2 - j);
        if (rest != 0) return rest;
        return s1.compareTo(s2);
    }

    private static String trimZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') i++;
        return s.substring(i);
    }
}
