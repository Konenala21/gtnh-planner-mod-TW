import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Lists every class, method and field our classes use that a pack's mods lack: what would throw NoClassDefFoundError,
 * NoSuchMethodError or NoSuchFieldError there. Mixins are checked by the names they target. A gap the code handles
 * (a catch, a fallback) is listed in guarded.txt; anything else fails the check.
 *
 * <p>Run through {@code ./gradlew compatCheck}. Args: ourClassesDir guarded.txt pack=classpathFile...
 */
public class Check {

    record Info(String sup, String[] itfs, Set<String> members) {}

    final Map<String, Info> cp = new HashMap<>();
    final Set<String> ours = new HashSet<>();
    /** Missing reference to the classes of ours that use it. */
    final TreeMap<String, TreeSet<String>> missing = new TreeMap<>();
    String cur;

    public static void main(String[] a) throws Exception {
        final Path classes = Path.of(a[0]);
        // A gap and the classes of ours whose use of it is handled: another class reaching it is a new gap.
        final Map<String, Set<String>> guarded = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of(a[1]))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            final int why = line.indexOf("  #");
            final String entry = (why < 0 ? line : line.substring(0, why)).trim();
            final int by = entry.indexOf("  <- ");
            if (by < 0) throw new IllegalArgumentException("guarded.txt: no '  <- ' list of the classes it covers: " + line);
            guarded.put(entry.substring(0, by).trim(), new TreeSet<>(List.of(entry.substring(by + 5).trim().split("\\s*,\\s*"))));
        }
        final Set<String> guardUsed = new HashSet<>();
        int failures = 0;
        for (int i = 2; i < a.length; i++) {
            final String pack = a[i].substring(0, a[i].indexOf('='));
            final Check c = new Check();
            for (String line : Files.readAllLines(Path.of(a[i].substring(pack.length() + 1)))) {
                if (!line.isBlank()) c.index(new File(line.trim()));
            }
            c.scanAll(classes);
            final List<String> open = new ArrayList<>();
            int handled = 0;
            for (var e : c.missing.entrySet()) {
                final Set<String> covered = guarded.get(e.getKey());
                if (covered != null) guardUsed.add(e.getKey());
                if (covered != null && covered.containsAll(e.getValue())) {
                    handled++;
                    continue;
                }
                final TreeSet<String> uncovered = new TreeSet<>(e.getValue());
                if (covered != null) uncovered.removeAll(covered);
                open.add(e.getKey() + "\n      used by " + String.join(", ", uncovered)
                    + (covered != null ? " (guarded.txt covers only " + String.join(", ", covered) + ")" : ""));
            }
            System.out.println(pack + ": " + (open.isEmpty() ? "ok" : open.size() + " unguarded") + (handled > 0 ? " (" + handled + " guarded)" : ""));
            for (String o : open) System.out.println("    " + o);
            failures += open.size();
        }
        for (String g : guarded.keySet()) {
            if (!guardUsed.contains(g)) System.out.println("note: guarded.txt lists a gap no pack has any more: " + g);
        }
        if (failures > 0) {
            System.out.println("\nEach would throw in that pack when reached. Guard it (catch the LinkageError, check the version,"
                + " or reach it by reflection), then add the line to tools/dev/compat/guarded.txt with the classes it covers and why it is safe.");
            System.exit(1);
        }
    }

    void index(File f) throws IOException {
        if (f.isDirectory()) {
            final Path root = f.toPath();
            try (var s = Files.walk(root)) {
                for (Path p : s.filter(p -> p.toString().endsWith(".class")).toList()) {
                    put(new ClassReader(Files.readAllBytes(p)));
                }
            }
            return;
        }
        if (!f.getName().endsWith(".jar")) return;
        try (ZipFile z = new ZipFile(f)) {
            for (var en = z.entries(); en.hasMoreElements();) {
                final ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                put(new ClassReader(z.getInputStream(e).readAllBytes()));
            }
        }
    }

    /** First one wins: the classpath lists the pack's mods before everything else. */
    void put(ClassReader r) {
        if (cp.containsKey(r.getClassName())) return;
        final Set<String> mem = new HashSet<>();
        r.accept(new ClassVisitor(Opcodes.ASM9) {

            @Override
            public FieldVisitor visitField(int acc, String n, String d, String s, Object v) {
                mem.add(n + ":" + d);
                mem.add("#" + n);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) {
                mem.add(n + d);
                mem.add("#" + n);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        cp.put(r.getClassName(), new Info(r.getSuperName(), r.getInterfaces(), mem));
    }

    void scanAll(Path root) throws IOException {
        final List<Path> files;
        try (var s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".class")).toList();
        }
        final List<byte[]> bytes = new ArrayList<>();
        for (Path p : files) bytes.add(Files.readAllBytes(p));
        for (byte[] b : bytes) {
            final ClassReader r = new ClassReader(b);
            ours.add(r.getClassName());
            cp.remove(r.getClassName());
            put(r);
        }
        for (byte[] b : bytes) scan(b);
    }

    static boolean jdk(String owner) {
        return owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("sun/") || owner.startsWith("jdk/")
            || owner.startsWith("com/sun/");
    }

    void miss(String what) {
        missing.computeIfAbsent(what, k -> new TreeSet<>()).add(cur);
    }

    /** A member up the hierarchy, ours and the game's included; the JDK's are looked up by name. */
    boolean has(String owner, String key, Set<String> seen) {
        if (owner == null || !seen.add(owner)) return false;
        if (jdk(owner)) return jdkHas(owner, key);
        final Info i = cp.get(owner);
        if (i == null) return false;
        if (i.members.contains(key)) return true;
        if (has(i.sup, key, seen)) return true;
        for (String it : i.itfs) if (has(it, key, seen)) return true;
        return false;
    }

    /** By name only: a descriptor's types may be the game's, which this JVM can't load. */
    static boolean jdkHas(String owner, String key) {
        final boolean field = key.contains(":") && !key.contains("(");
        final String name = key.startsWith("#") ? key.substring(1)
            : field ? key.substring(0, key.indexOf(':')) : key.substring(0, key.indexOf('('));
        if (name.equals("<init>")) return true;
        try {
            for (Class<?> c = Class.forName(owner.replace('/', '.'), false, null); c != null; c = c.getSuperclass()) {
                if (field || key.startsWith("#")) for (var f : c.getDeclaredFields()) if (f.getName().equals(name)) return true;
                if (!field) {
                    for (var m : c.getDeclaredMethods()) if (m.getName().equals(name)) return true;
                    for (Class<?> i : c.getInterfaces()) for (var m : i.getMethods()) if (m.getName().equals(name)) return true;
                }
            }
        } catch (Throwable t) {
            return true;
        }
        return false;
    }

    /** An internal name, or an array's descriptor (what a type instruction names). */
    void cls(String t) {
        if (t == null) return;
        if (t.startsWith("[")) {
            type(Type.getType(t));
            return;
        }
        if (jdk(t) || ours.contains(t)) return;
        if (!cp.containsKey(t)) miss("CLASS " + t);
    }

    /** A field or method descriptor's classes. */
    void desc(String d) {
        final Type m = Type.getType(d);
        if (m.getSort() == Type.METHOD) {
            type(m.getReturnType());
            for (Type x : m.getArgumentTypes()) type(x);
        } else type(m);
    }

    void type(Type t) {
        if (t.getSort() == Type.ARRAY) t = t.getElementType();
        if (t.getSort() == Type.OBJECT) cls(t.getInternalName());
    }

    void member(String owner, String key, String shown) {
        if (owner.startsWith("[") || jdk(owner)) return;
        if (!cp.containsKey(owner)) {
            cls(owner);
            return;
        }
        if (!has(owner, key, new HashSet<>())) miss("MEMBER " + owner + "." + shown);
    }

    void handle(Object o) {
        if (o instanceof Handle h) {
            if (h.getTag() <= Opcodes.H_PUTSTATIC) member(h.getOwner(), h.getName() + ":" + h.getDesc(), h.getName() + " : " + h.getDesc());
            else member(h.getOwner(), h.getName() + h.getDesc(), h.getName() + h.getDesc());
            desc(h.getDesc());
        } else if (o instanceof Type t) {
            if (t.getSort() == Type.METHOD) desc(t.getDescriptor());
            else type(t);
        }
    }

    void scan(byte[] b) {
        final ClassReader r = new ClassReader(b);
        cur = r.getClassName().replaceAll("\\$.*", "").replace("com/gtnhplanner/", "");
        final List<String> targets = new ArrayList<>();
        /** Names a mixin reaches in its targets: injected methods, accessors, shadows. */
        final List<String> names = new ArrayList<>();
        cls(r.getSuperName());
        for (String i : r.getInterfaces()) cls(i);
        r.accept(new ClassVisitor(Opcodes.ASM9) {

            @Override
            public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                if (!d.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {

                            @Override
                            public void visit(String n, Object v) {
                                targets.add(v instanceof Type t ? t.getInternalName() : ((String) v).replace('.', '/'));
                            }
                        };
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int acc, String n, String d, String s, Object v) {
                desc(d);
                return new FieldVisitor(Opcodes.ASM9) {

                    @Override
                    public AnnotationVisitor visitAnnotation(String ad, boolean visible) {
                        if (ad.equals("Lorg/spongepowered/asm/mixin/Shadow;")) names.add("#" + n);
                        return null;
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) {
                desc(d);
                if (ex != null) for (String e : ex) cls(e);
                return new MethodVisitor(Opcodes.ASM9) {

                    @Override
                    public AnnotationVisitor visitAnnotation(String ad, boolean visible) {
                        if (ad.equals("Lorg/spongepowered/asm/mixin/Shadow;")) names.add("#" + n);
                        if (!ad.startsWith("Lorg/spongepowered/asm/mixin/")) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {

                            @Override
                            public void visit(String key, Object v) {
                                // @Accessor("xSize"), @Invoker("foo")
                                if (key.equals("value") && v instanceof String str && !str.isEmpty()) names.add("#" + str);
                            }

                            @Override
                            public AnnotationVisitor visitArray(String key) {
                                if (!key.equals("method")) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {

                                    @Override
                                    public void visit(String k, Object v) {
                                        String m = (String) v;
                                        if (m.indexOf(';') >= 0 && m.indexOf(';') < Math.max(m.indexOf('('), 0)) m = m.substring(m.indexOf(';') + 1);
                                        names.add("#" + (m.contains("(") ? m.substring(0, m.indexOf('(')) : m));
                                    }
                                };
                            }
                        };
                    }

                    @Override
                    public void visitTypeInsn(int op, String t) {
                        cls(t);
                    }

                    @Override
                    public void visitFieldInsn(int op, String o, String n2, String d2) {
                        member(o, n2 + ":" + d2, n2 + " : " + d2);
                        desc(d2);
                    }

                    @Override
                    public void visitMethodInsn(int op, String o, String n2, String d2, boolean itf) {
                        member(o, n2 + d2, n2 + d2);
                        desc(d2);
                    }

                    @Override
                    public void visitLdcInsn(Object v) {
                        handle(v);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n2, String d2, Handle bsm, Object... args) {
                        desc(d2);
                        for (Object x : args) handle(x);
                    }

                    @Override
                    public void visitTryCatchBlock(Label s1, Label e1, Label h, String t) {
                        if (t != null) cls(t);
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String d2, int dims) {
                        desc(d2);
                    }
                };
            }
        }, 0);
        for (String t : targets) {
            if (!cp.containsKey(t) && !jdk(t)) {
                miss("MIXIN TARGET " + t);
                continue;
            }
            for (String n : names) {
                if (!n.equals("#<init>") && !has(t, n, new HashSet<>())) miss("MIXIN TARGET " + t + "." + n.substring(1));
            }
        }
    }
}
