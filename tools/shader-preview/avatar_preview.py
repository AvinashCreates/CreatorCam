import sys, numpy as np, moderngl
sys.path.insert(0, __import__('os').path.dirname(__file__))
import glharness as H
from PIL import Image, ImageDraw
ctx = H.ctx; quad = H.quad
VS = H.to330(H.kt_const('AVATAR_VERTEX'), 'vs'); FS = H.to330(H.kt_const('AVATAR_FRAGMENT'), 'fs')
prog = ctx.program(vertex_shader=VS, fragment_shader=FS)
vao = ctx.vertex_array(prog, [(quad, '2f', 'aPos')])

SKIN = [(0xFD,0xDB,0xB4),(0xF1,0xC2,0x7D),(0xE0,0xAC,0x69),(0xC6,0x86,0x42),(0x8D,0x55,0x24),(0x5C,0x3A,0x21)]
HAIR = [(0x1E,0x1A,0x1A),(0x4A,0x2E,0x1F),(0x8A,0x3B,0x1C),(0xE3,0xB8,0x5B),(0xC9,0xCE,0xD6),(0xE0,0x56,0x8F),(0x3E,0x7B,0xDB)]
EYE = [(0x5B,0x3A,0x22),(0x3B,0x82,0xC4),(0x3F,0x8F,0x5A),(0x7A,0x85,0x90)]
def rgb(t): return tuple(v/255 for v in t)

def draw(size=400, skin=0, hair=1, eye=0, style=0, acc=0, yaw=0, pitch=0, eyes=(1,1), smile=0, mouth=0, alpha=1.0, bg=(0.55,0.62,0.7)):
    fbo = ctx.simple_framebuffer((size, size)); fbo.use(); fbo.clear(*bg, 1.0)
    ctx.enable(moderngl.BLEND); ctx.blend_func = (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)
    def u(n, v):
        if n in prog: prog[n].value = v
    u('uCenter', (0.0, 0.0)); u('uSizePx', float(size)); u('uRot', 0.0); u('uView', (float(size), float(size)))
    u('uAA', 4.0/size); u('uAlpha', alpha)
    u('uSkin', rgb(SKIN[skin])); u('uHair', rgb(HAIR[hair])); u('uEye', rgb(EYE[eye]))
    u('uHairStyle', float(style)); u('uAcc', float(acc)); u('uLook', (yaw, pitch)); u('uEyes', eyes); u('uSmile', smile); u('uMouth', mouth)
    vao.render(moderngl.TRIANGLE_STRIP)
    ctx.disable(moderngl.BLEND)
    return Image.fromarray(np.frombuffer(fbo.read(components=3), 'u1').reshape(size, size, 3)[::-1])

def sheet(items, cols, path, size=300, labels=None):
    rows = (len(items)+cols-1)//cols
    sh = Image.new('RGB', (cols*size, rows*(size+16)), (20,20,24)); d = ImageDraw.Draw(sh)
    for i, kw in enumerate(items):
        k = dict(kw); lab = k.pop('label', '')
        im = draw(size=size, **k); x, y = (i%cols)*size, (i//cols)*(size+16)
        sh.paste(im, (x, y+16)); d.text((x+4, y+2), lab, fill=(255,255,255))
    sh.save(path); print('saved', path)

if __name__ == '__main__':
    presets = [
      dict(label='Alex short/brown', skin=0, hair=1, eye=0, style=0, acc=0),
      dict(label='Sam long/black glasses', skin=3, hair=0, eye=0, style=1, acc=1),
      dict(label='Riley curly/auburn', skin=2, hair=2, eye=2, style=2, acc=0),
      dict(label='Jordan bun/blonde phones', skin=0, hair=3, eye=1, style=3, acc=3),
      dict(label='Casey short/blue shades', skin=4, hair=6, eye=0, style=0, acc=2),
      dict(label='Robin bald beanie', skin=5, hair=0, eye=3, style=4, acc=4),
    ]
    sheet(presets, 3, './avatar_presets.png')
    expr = [
      dict(label='neutral'), dict(label='smile', smile=1.0), dict(label='open mouth', mouth=0.9),
      dict(label='big grin (smile+open)', smile=1.0, mouth=0.6), dict(label='blink', eyes=(0.0,0.0)), dict(label='wink L', eyes=(0.0,1.0)),
      dict(label='yaw +0.8', yaw=0.8, smile=0.3), dict(label='yaw -0.8', yaw=-0.8, smile=0.3), dict(label='pitch up', pitch=0.9),
    ]
    sheet(expr, 3, './avatar_expr.png')
