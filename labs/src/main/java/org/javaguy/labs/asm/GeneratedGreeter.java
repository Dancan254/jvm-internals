package org.javaguy.labs.asm;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;

public class GeneratedGreeter implements Opcodes {

    static final String GREETING = "Hello from Greet — a class that did not exist a second ago!";

    public static void main(String[] args) throws Exception {
        byte[] classBytes = buildGreetClass();

        Files.write(Path.of("Greet.class"), classBytes);
        IO.println("Wrote Greet.class (" + classBytes.length + " bytes)");

        Class<?> greetClass = MethodHandles.lookup().defineClass(classBytes);
        IO.println("Loaded " + greetClass.getName() + " with " + greetClass.getClassLoader());

        greetClass.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
    }

    static byte[] buildGreetClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

        cw.visit(V25, ACC_PUBLIC | ACC_SUPER, "org/javaguy/labs/asm/Greet",
                null, "java/lang/Object", null);

        MethodVisitor constructor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor main = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "main",
                "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(GREETING);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println",
                "(Ljava/lang/String;)V", false);
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
