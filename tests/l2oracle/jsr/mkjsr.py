import struct
def u2(w): return struct.pack('>H', w)
def u4(d): return struct.pack('>I', d)
cp = []
def put(b): cp.append(b); return len(cp)
def utf(s): return put(b'\x01' + u2(len(s)) + s.encode('ascii'))
def cls(n): return put(b'\x07' + u2(n))
def nt(n, d): return put(b'\x0c' + u2(n) + u2(d))
def mr(c, n): return put(b'\x0a' + u2(c) + u2(n))
def fr(c, n): return put(b'\x09' + u2(c) + u2(n))
i_JsrProbe = utf('JsrProbe')          # 1
i_Object = utf('java/lang/Object')    # 2
i_init = utf('<init>')                # 3
i_void = utf('()V')                   # 4
i_Code = utf('Code')                  # 5
i_run = utf('run')                    # 6
i_ii = utf('()I')                     # 7
i_main = utf('main')                  # 8
i_args = utf('([Ljava/lang/String;)V')# 9
i_System = utf('java/lang/System')    # 10
i_out = utf('out')                    # 11
i_PS = utf('Ljava/io/PrintStream;')   # 12
i_println = utf('println')            # 13
i_IV = utf('(I)V')                    # 14
i_PrintStream = utf('java/io/PrintStream') # 15
c_this = cls(i_JsrProbe)              # 16
c_obj = cls(i_Object)                 # 17
c_sys = cls(i_System)                 # 18
c_ps = cls(i_PrintStream)             # 19
nt_init = nt(i_init, i_void)          # 20
mr_objinit = mr(c_obj, nt_init)       # 21
nt_out = nt(i_out, i_PS)              # 22
fr_sysout = fr(c_sys, nt_out)         # 23
nt_pln = nt(i_println, i_IV)          # 24
mr_pln = mr(c_ps, nt_pln)             # 25
nt_run = nt(i_run, i_ii)              # 26
mr_run = mr(c_this, nt_run)           # 27
assert mr_run == 27, mr_run
def method(access, name_idx, desc_idx, max_stack, max_locals, code):
    attr = u2(i_Code) + u4(12 + len(code)) + u2(max_stack) + u2(max_locals) \
        + u4(len(code)) + code + u2(0) + u2(0)
    return u2(access) + u2(name_idx) + u2(desc_idx) + u2(1) + attr
init_code = bytes([0x2A, 0xB7]) + u2(mr_objinit) + bytes([0xB1])
run_code = bytes([0xA8,0,10, 0x1B,0xAC, 0,0,0,0,0, 0x4B,0x10,42, 0x3C,0xA9,0])
assert len(run_code) == 16
main_code = bytes([0xB2]) + u2(fr_sysout) + bytes([0xB8]) + u2(mr_run) \
    + bytes([0xB6]) + u2(mr_pln) + bytes([0xB1])
m1 = method(0x0001, i_init, i_void, 1, 1, init_code)
m2 = method(0x0009, i_run, i_ii, 1, 2, run_code)
m3 = method(0x0009, i_main, i_args, 2, 1, main_code)
out = b'\xCA\xFE\xBA\xBE' + u2(0) + u2(49) + u2(len(cp)+1) + b''.join(cp) \
    + u2(0x0021) + u2(c_this) + u2(c_obj) + u2(0) + u2(0) + u2(3) + m1 + m2 + m3 + u2(0)
open('/tmp/jsr/JsrProbe.class','wb').write(out)
print('wrote', len(out), 'bytes')

# JsrGen.java: byte-exact re-emitter for the guest (binary push mangles
# class files, so the guest rebuilds JsrProbe.class from this source).
data = open('/tmp/jsr/JsrProbe.class','rb').read()
lines = []
for i in range(0, len(data), 12):
    lines.append('        ' + ', '.join(str(b) for b in data[i:i+12]) + ',')
gsrc = 'public class JsrGen {' + chr(10) + '    static final int[] BYTES = new int[] {' + chr(10)
gsrc += chr(10).join(lines) + chr(10) + '    };' + chr(10)
gsrc += '    public static void main(String[] a) throws Exception {' + chr(10)
gsrc += '        byte[] b = new byte[BYTES.length];' + chr(10)
gsrc += '        for (int i = 0; i < BYTES.length; i++) { b[i] = (byte) BYTES[i]; }' + chr(10)
gsrc += '        java.io.FileOutputStream f = new java.io.FileOutputStream("JsrProbe.class");' + chr(10)
gsrc += '        f.write(b);' + chr(10) + '        f.close();' + chr(10) + '    }' + chr(10) + '}' + chr(10)
open('/tmp/jsr/JsrGen.java','w').write(gsrc)
print('gen src bytes:', len(gsrc))
