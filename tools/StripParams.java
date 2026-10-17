import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.stream.*;

/** Removes MethodParameters attributes (old D8 crashes on unnamed mandated params emitted by javac 21). */
public class StripParams {
    public static void main(String[] a) throws Exception {
        try (Stream<Path> s = Files.walk(Paths.get(a[0]))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".class")).collect(Collectors.toList())) {
                ClassReader cr = new ClassReader(Files.readAllBytes(p));
                ClassWriter cw = new ClassWriter(0);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override public MethodVisitor visitMethod(int acc, String n, String d, String sig, String[] ex) {
                        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(acc, n, d, sig, ex)) {
                            @Override public void visitParameter(String name, int access) { }
                        };
                    }
                }, 0);
                Files.write(p, cw.toByteArray());
            }
        }
    }
}
