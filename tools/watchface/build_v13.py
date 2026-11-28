import sys; sys.path.insert(0,'/home/claude/build/w'); sys.path.insert(0,'/home/claude/build')
import os; os.chdir('/home/claude/build/w')
import faces, face7
from faces import combine, done
from PIL import Image
kw=dict(y0=34,big=True,battapp='x14f3',lownum=True)
L=face7.tide2('t13L','icon',bleft=True,**kw); R=face7.tide2('t13R','icon',bleft=False,**kw)
print(combine([L,R],'DK_07_v13',"DoubleK's 7",'167210925'))
# 확인용 그림: 배터리 값별로 실제 목록 규칙대로 합성
def pick(files,v): return [n for t,n in files if t<=v][-1]
out=[]
for F in (L,R):
    for v in (100,21,20,10,6,5,3,0):
        im=Image.open(F.dir+'/pv_full.png').convert('RGBA').crop((0,0,432,90))
        bg=Image.open(F.img+'/bg.png').convert('RGBA').crop((0,0,432,90))
        im=bg
        for w in F.widgets:
            if w['t']=='list' and w['name'] in('BattIcon','BattNum'):
                im.alpha_composite(Image.open(f"{F.img}/{pick(w['files'],v)}.png").convert('RGBA'),(w['x'],w['y']))
        out.append(im)
sheet=Image.new('RGB',(432*2,90*8))
for i,im in enumerate(out): sheet.paste(im.convert('RGB'),((i//8)*432,(i%8)*90))
sheet.save('/home/claude/work/v13_batt.png')
for F in (L,R):
    for w in F.widgets:
        if w['t']=='list' and w['name'].startswith('Batt'): print(F.key,w['name'],w['x'],w['y'],w['w'],w['files'][:3],'...',w['files'][-2:])
    print(F.key,[w['name'] for w in F.widgets if w['t']=='num'])
done()
