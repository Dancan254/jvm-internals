package org.javaguy.labs.jol;

import org.openjdk.jol.info.ClassLayout;

public class LayoutTour {

    static class IntAndBoolean {
        int count;
        boolean flag;
    }

    public static void main(String[] args) {
        print("an empty object", new Object());
        print("an int and a boolean", new IntAndBoolean());
        print("an int[7]", new int[7]);
        print("a String", "layout");
    }

    static void print(String title, Object instance) {
        IO.println("=== " + title + " ===");
        IO.println(ClassLayout.parseInstance(instance).toPrintable());
    }
}
