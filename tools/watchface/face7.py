import sys; sys.path.insert(0,'/home/claude/build')
import faces
from faces import Face, combine, done
Face.COMPILE=False
NS={}
def load(fn,marker,ns=NS):
    s=open(fn,encoding='utf-8').read(); exec(s[:s.index(marker)],ns)
load('face2.py',"halo('DoubleKs_4'")
NS3d={}; load('face3d.py',"tide(); done()",NS3d)
g=NS3d; KR,I,WH=g['KR'],g['I'],'#fff'; A,T,ic,glass=g['A'],g['T'],g['ic'],g['glass']
LEVELS=[5,10,20,30,40,50,60,70,80,90,100]
LOWNUM=False   # True: 배터리 숫자는 20% 이하에서만 보이고(그때 아이콘 속은 노랑), 그 위에서는 아이콘만 (사용자 요청 2026-11-28)
def bicon(p):
    col=('#FF453A' if p<=5 else ('#FFD60A' if p<=20 else '#fff')) if LOWNUM else ('#FF453A' if p<=10 else ('#FFD60A' if p<=20 else '#fff'))
    fw=max({1:2,3:3,5:4,10:5,15:6,20:7}.get(p,3),round(30*p/100)) if LOWNUM else {5:3,10:5}.get(p,round(30*p/100))
    return (f'<svg width="46" height="24" style="position:absolute;left:0;top:0"><rect x="1" y="1" width="38" height="22" rx="7" fill="none" stroke="#fff" stroke-width="2" opacity=".9"/>'
            f'<rect x="41" y="8" width="4" height="8" rx="2" fill="#fff" opacity=".9"/><rect x="5" y="5" width="{fw}" height="14" rx="3.5" fill="{col}"/></svg>')
def tide2(key,batt,y0=22,wy=None,big=False,battapp=None,bleft=False,live=False,title='x',fid='0',compile=False,lownum=False):
    global LOWNUM; LOWNUM=lownum
    Y0=y0 if batt=='icon' else 0   # 배터리가 우상단에 있으면 나머지를 전체적으로 내림
    DY=8+Y0                    # 아이콘·걸음 줄을 살짝 아래로
    f=Face(key,'x','0'); f.bgcss='linear-gradient(180deg,#071B33 0%,#0B3A63 100%)'
    f.static+=f'<svg width="432" height="514" style="position:absolute;left:0;top:{Y0 if wy is None else wy}px"><path d="M0 306 C80 280 150 330 230 306 S380 282 432 306 V514 H0Z" fill="#0E5E8C"/><path d="M0 356 C90 332 160 380 250 356 S390 336 432 356 V514 H0Z" fill="#1287A8"/><path d="M0 414 C70 396 170 432 260 411 S400 394 432 414 V514 H0Z" fill="#35B8C0"/></svg>'
    f.date(f'600 26px {KR}','rgba(255,255,255,.9)',36,56+Y0,['m','d','w'])
    f.time(f'400 100px {I}',WH,88+Y0,110,-3)
    f.static+=glass(40,228+Y0,168,60,A(16,10,ic('weather',40,WH)))+glass(224,228+Y0,168,60,A(16,10,ic('heart',40,WH)))
    VF=f'600 30px {I}'; vw=f.digits('val',VF,WH,40); f.numsuf('Temp',2031,'val',VF,WH,vw,40,238+Y0,2,'°',2,left=104); f.num('HeartRate',822,'val',vw,40,288,238+Y0,3,'l')
    # 메신저 · 타이머 · 걸음 수: 같은 간격 (걸음 수 4자리 기준), 걸음 수는 가운데 정렬
    SF=f'600 44px {I}'; sw=f.digits('st',SF,WH,54)
    CX=392-2*sw-sw//2                     # 걸음 수 가운데 (5자리일 때 오른쪽 끝이 392)
    sl=CX-2*sw                            # 4자리일 때 왼쪽 끝
    gap=(sl-92-52)//2; tx=92+gap          # 메신저(40~92) | 타이머 | 걸음
    f.static+=A(40,338+DY,ic('chat',52,WH))+A(tx,338+DY,ic('timer',52,WH))+T(CX-80,384+DY,160,'걸음',f'600 20px {KR}','rgba(255,255,255,.88)','center')
    f.num('Steps',821,'st',sw,54,CX-5*sw//2,328+DY,5,'c')
    BF=f'600 30px {I}'; bw=f.digits('bat',BF,WH,40)
    if batt=='icon':
        files=[]
        LV=[1,3,5,10,15,20,25,30,35,40,90,95,100] if lownum else LEVELS   # 사용자 지정 단계(08:08): 41~90 은 모두 90 모양   # 21~40 은 모두 40 모양(35·30·25 건너뜀, 사용자 요청 08:06) → 40 다음이 바로 노랑 20   # lownum: 5% 단위 20단계 (사용자 요청 2026-11-28), 구간의 위쪽 값으로 그림(적어 보이지 않게)
        for i,p in enumerate(LV):
            f.shot(bicon(p),f'bi_{p}',46,24); files.append((0 if i==0 else LV[i-1]+1,f'bi_{p}'))
        # 우상단: 숫자(오른쪽 정렬) % 아이콘
        BF=f'600 24px {I}'; bw=f.digits('bat',BF,WH,30); pw=f.tw('%',BF); IX,IY=346,30
        if lownum:
            # 숫자 위젯은 조건부로 숨길 수 없음 → 0~20 은 'N%' 그림 21장, 21 이상은 빈 그림인 목록으로 대신함
            if bleft: IX=40
            NW=3*bw+pw+2; x0=IX+46+8 if bleft else IX-8-NW
            nf=[]
            for n in range(21):
                f.shot(f'<div style="position:absolute;left:0;top:0;width:{NW}px;height:30px;text-align:{"left" if bleft else "right"};font:{BF};line-height:30px;color:#fff;white-space:nowrap;font-variant-numeric:tabular-nums">{n}%</div>',f'bn_{n}',NW,30); nf.append((n,f'bn_{n}'))
            from PIL import Image as _I; _I.new('RGBA',(NW,30),(0,0,0,0)).save(f'{f.img}/bn_none.png'); nf.append((21,'bn_none'))
            f.widgets.append(dict(t='list',name='BattIcon',x=IX,y=IY,w=46,h=24,src=841,files=files))
            f.widgets.append(dict(t='list',name='BattNum',x=x0,y=IY-3,w=NW,h=30,src=841,files=nf))
        elif bleft:   # 좌상단: 아이콘 숫자%
            IX=40
            f.widgets.append(dict(t='list',name='BattIcon',x=IX,y=IY,w=46,h=24,src=841,files=files))
            # 숫자는 왼쪽 정렬, % 는 자릿수(1·2·3자리)에 따라 위치가 다른 그림 3장으로 → 항상 '아이콘 숫자%'가 붙어 보임
            x0=IX+46+8; f.num('Battery',841,'bat',bw,30,x0,IY-3,3,'l')
            pf=[]
            for k,th in ((1,0),(2,10),(3,100)):
                f.shot(f'<div style="position:absolute;left:{k*bw}px;top:0;height:30px;font:{BF};line-height:30px;color:#fff">%</div>',f'bpct_{k}',3*bw+pw+2,30); pf.append((th,f'bpct_{k}'))
            f.widgets.append(dict(t='list',name='BattPct',x=x0,y=IY-3,w=3*bw+pw+2,h=30,src=841,files=pf))
        else:
            f.widgets.append(dict(t='list',name='BattIcon',x=IX,y=IY,w=46,h=24,src=841,files=files))
            f.static+=f'<div style="position:absolute;left:{IX-8-pw}px;top:{IY-3}px;height:30px;font:{BF};line-height:30px;color:#fff">%</div>'
            f.num('Battery',841,'bat',bw,30,IX-8-pw-3*bw,IY-3,3,'r')
    else:
        f.numsuf('Battery',841,'bat',BF,WH,bw,40,436,3,'%',2,left=50)
    if big:   # 사용자가 그린 흰 네모 크기 (y0=34 기준)
        # 워치는 가로로 긴 버튼의 왼쪽(대략 높이만큼)만 인식함(2026-11-22 사용자 확인)
        # → 넓은 영역은 정사각형 버튼 여러 개로 나눠 덮음
        def tiles(app,x,y,w,h):
            n=max(1,-(-w//h)); 
            for k in range(n): f.tap(app,x+(0 if n==1 else round(k*(w-h)/(n-1))),y,min(w,h),h)
        tiles('weather',26,244,182,94); tiles('hrm',224,244,184,94); tiles('wechat',13,362,100,102); tiles('timer',131,362,93,102); tiles('steps',234,362,175,102)
        tiles('calendar',115,84,204,50)
        if battapp: tiles(battapp,0 if bleft else 256,0,176,74)
    else:
        f.tap('weather',40,222+Y0,168,72); f.tap('hrm',224,222+Y0,168,72); f.tap('wechat',28,328+DY,76,72); f.tap('timer',tx-12,328+DY,76,72); f.tap('steps',CX-85,322+DY,170,90)
    if live:   # 시험: 안 보이는 '초' 숫자를 넣어 워치가 매초 화면을 새로 그리게 함 → 걸음 수도 자주 갱신되는지
        if live=='show':   # 시험판: 초가 눈에 보임
            zf=f'600 26px {I}'; zw=f.digits('sec',zf,'#FFD60A',36); f.num('Second',1811,'sec',zw,36,216-zw,470,2,'r')
        elif live=='bg':   # 배경과 같은 색의 불투명한 초 숫자 (눈에는 안 보이지만 워치는 매초 다시 그림 → 걸음 수 실시간)
            zf=f'600 12px {I}'; zw=f.digits('sec',zf,'#071B33',14); f.num('Second',1811,'sec',zw,14,216-zw,1,2,'r')
        else:
            zw=f.digits('sec',f'400 8px {I}','rgba(0,0,0,0)',8); f.num('Second',1811,'sec',zw,8,208,2,2,'r')
    f.title,f.fid=title,fid
    f.build(compile=compile,outname=key) if compile else f.build(compile=False); print(key,'sw',sw,'tx',tx,'CX',CX,'gap',gap); return f
if __name__=='__main__':
    a=tide2('t7a','icon'); b=tide2('t7b','text')
    if len(sys.argv)>1:
        NS['violet']('t7c','x','0'); c=Face.ALL[-1]
        combine([a,b,c],'DK_07_v4',"DoubleK's 7",sys.argv[1])
    if len(sys.argv)>2:
        load('face3.py',"a=meshprism('s11a'")
        NS['home']('t9a','x','0','a'); h=Face.ALL[-1]; NS['clayf'](); cl=Face.ALL[-1]
        combine([h,cl],'DK_09_v2',"DoubleK's 9",sys.argv[2])
    done()
