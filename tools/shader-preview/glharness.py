import re, json, numpy as np, moderngl
from PIL import Image, ImageDraw, ImageFont

import pathlib
SHADERS = (pathlib.Path(__file__).resolve().parents[2] / 'app/src/main/java/com/avinash/creatorcam/gl/Shaders.kt').read_text()
def kt_const(name):
    m = re.search(r'const val %s = """(.*?)"""' % name, SHADERS, re.S)
    return m.group(1)

def to330(src, stage):
    src = re.sub(r'#extension[^\n]*\n', '', src)
    src = re.sub(r'precision\s+\w+\s+float;', '', src)
    src = src.replace('samplerExternalOES', 'sampler2D').replace('texture2D', 'texture')
    if stage == 'vs':
        src = re.sub(r'\battribute\b', 'in', src); src = re.sub(r'\bvarying\b', 'out', src)
    else:
        src = re.sub(r'\bvarying\b', 'in', src)
        src = src.replace('gl_FragColor', 'fragColor')
        src = 'out vec4 fragColor;\n' + src
    return '#version 330\n' + src

ctx = moderngl.create_standalone_context(backend='egl', require=330)
quad = ctx.buffer(np.array([-1,-1, 1,-1, -1,1, 1,1], 'f4').tobytes())

def make_scene(w, h):
    """Upright scene (top-down array): coloured corners + a big 'F' + an arrow, so any flip/rotation is obvious."""
    im = Image.new('RGB', (w, h), (40, 40, 48)); d = ImageDraw.Draw(im)
    s = min(w, h) // 4
    d.rectangle([0,0,s,s], fill=(230,40,40)); d.rectangle([w-s,0,w,s], fill=(40,200,60))
    d.rectangle([0,h-s,s,h], fill=(50,80,240)); d.rectangle([w-s,h-s,w,h], fill=(240,220,40))
    x0, y0, bar = w*0.30, h*0.30, w*0.07
    d.rectangle([x0, y0, x0+bar, y0+h*0.40], fill=(255,255,255))
    d.rectangle([x0, y0, x0+w*0.30, y0+bar], fill=(255,255,255))
    d.rectangle([x0, y0+h*0.18, x0+w*0.22, y0+h*0.18+bar], fill=(255,255,255))
    return np.asarray(im)

def render(prog_src, raw, m, effect=None, out_w=270, out_h=480):
    vs, fs = to330(kt_const('CAMERA_VERTEX'), 'vs'), to330(kt_const('CAMERA_FRAGMENT'), 'fs')
    prog = ctx.program(vertex_shader=vs, fragment_shader=fs)
    tex = ctx.texture((raw.shape[1], raw.shape[0]), 3, np.ascontiguousarray(raw).tobytes())
    tex.filter = (moderngl.LINEAR, moderngl.LINEAR); tex.repeat_x = tex.repeat_y = False
    tex.use(0)
    e = dict(uBrightness=0.0, uContrast=1.0, uSaturation=1.0, uSharpen=0.0, uSoft=0.0, uVignette=0.0, uMono=0.0, uFade=0.0)
    if effect: e.update(effect)
    if 'uTex' in prog: prog['uTex'].value = 0
    prog['uTexMat'].write(np.array(m, 'f4').tobytes())
    prog['uTexel'].value = (1.0/raw.shape[1], 1.0/raw.shape[0]); prog['uTint'].value = (1,1,1)
    for k, v in e.items(): prog[k].value = v
    vao = ctx.vertex_array(prog, [(quad, '2f', 'aPos')])
    fbo = ctx.simple_framebuffer((out_w, out_h)); fbo.use(); fbo.clear(0,0,0,1)
    vao.render(moderngl.TRIANGLE_STRIP)
    img = np.frombuffer(fbo.read(components=3), dtype=np.uint8).reshape(out_h, out_w, 3)[::-1]   # GL rows are bottom-up
    return img

def expected(scene, cx, cy, mirror, out_w=270, out_h=480):
    h, w = scene.shape[:2]
    cw, ch = int(round(w*cx)), int(round(h*cy))
    x0, y0 = (w-cw)//2, (h-ch)//2
    crop = scene[y0:y0+ch, x0:x0+cw]
    im = Image.fromarray(crop).resize((out_w, out_h), Image.BILINEAR)
    a = np.asarray(im)
    return a[:, ::-1] if mirror else a

if __name__ == '__main__':
    cases = json.load(open('/tmp/jvmtest/matrices.json'))
    bad = 0; worst = 0
    sheet = []
    for c in cases:
        th, bw, bh = c['theta'], c['bw'], c['bh']
        up_w, up_h = (bh, bw) if th in (90, 270) else (bw, bh)
        scene = make_scene(up_w, up_h)
        raw = np.rot90(scene, th // 90)               # raw buffer = scene rotated CCW by theta (CW theta makes it upright)
        assert raw.shape[1] == bw and raw.shape[0] == bh, (raw.shape, bw, bh)
        out = render(None, raw, c['m'])
        exp = expected(scene, c['cx'], c['cy'], c['mirror'])
        err = np.abs(out.astype(int) - exp.astype(int)).mean() / 255
        worst = max(worst, err)
        if err > 0.03: bad += 1; print('MISMATCH', {k: c[k] for k in ('theta','hint','mirror','bw','bh')}, 'err=%.3f' % err)
        if (c['theta'], c['hint'], c['bw']) in ((90,90,1280),(270,90,1280),(0,0,1280),(90,0,640)):
            sheet.append((c, out, exp))
    print('GL render check: %d configs, mismatches=%d, worst mean-abs-error=%.4f' % (len(cases), bad, worst))
    # contact sheet: rendered (top) vs expected (bottom) for a few configs
    cell_w, cell_h = 270, 480
    sh = Image.new('RGB', (cell_w*len(sheet), cell_h*2+24), (0,0,0)); d = ImageDraw.Draw(sh)
    for i, (c, out, exp) in enumerate(sheet):
        sh.paste(Image.fromarray(out), (i*cell_w, 0)); sh.paste(Image.fromarray(exp), (i*cell_w, cell_h+24))
        d.text((i*cell_w+4, cell_h+6), 'sensor%d hint%d mirror=%s %dx%d' % (c['theta'], c['hint'], c['mirror'], c['bw'], c['bh']), fill=(255,255,255))
    sh.save('/tmp/gltest/orientation_sheet.png')
