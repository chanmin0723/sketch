import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Rewrites method refs whose owner is an interface to InterfaceMethodref (dex2jar output fix). */
public class FixItf {
    public static void main(String[] a) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(a[0]))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) entries.put(e.getName(), zin.readAllBytes());
        }
        final Set<String> itfs = new HashSet<>();
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            if (!en.getKey().endsWith(".class")) continue;
            ClassReader cr = new ClassReader(en.getValue());
            if ((cr.getAccess() & Opcodes.ACC_INTERFACE) != 0) itfs.add(cr.getClassName());
        }
        final int[] fixed = {0};
        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(a[1]))) {
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                byte[] data = en.getValue();
                if (en.getKey().endsWith(".class")) {
                    ClassReader cr = new ClassReader(data);
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                        @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) {
                            return new MethodVisitor(Opcodes.ASM9, super.visitMethod(acc, n, d, s, ex)) {
                                @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                                    boolean want = isItf(owner, itfs);
                                    if (op != Opcodes.INVOKEVIRTUAL && want != itf) { itf = want; fixed[0]++; }
                                    if (op == Opcodes.INVOKEVIRTUAL && want) { op = Opcodes.INVOKEINTERFACE; itf = true; fixed[0]++; }
                                    super.visitMethodInsn(op, owner, name, desc, itf);
                                }
                            };
                        }
                    }, 0);
                    data = cw.toByteArray();
                }
                zout.putNextEntry(new ZipEntry(en.getKey()));
                zout.write(data);
                zout.closeEntry();
            }
        }
        System.out.println("fixed refs: " + fixed[0]);
    }

    static boolean isItf(String owner, Set<String> itfs) {
        if (itfs.contains(owner)) return true;
        if (owner.startsWith("[")) return false;
        try {
            return Class.forName(owner.replace('/', '.'), false, FixItf.class.getClassLoader()).isInterface();
        } catch (Throwable t) { return false; }
    }
}
