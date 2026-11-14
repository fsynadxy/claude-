import sys, os, torch, torch.nn as nn, torch.nn.functional as F, numpy as np
from PIL import Image
class RDB(nn.Module):
    def __init__(s, nf=64, gc=32):
        super().__init__()
        s.conv1=nn.Conv2d(nf,gc,3,1,1); s.conv2=nn.Conv2d(nf+gc,gc,3,1,1); s.conv3=nn.Conv2d(nf+2*gc,gc,3,1,1)
        s.conv4=nn.Conv2d(nf+3*gc,gc,3,1,1); s.conv5=nn.Conv2d(nf+4*gc,nf,3,1,1); s.l=nn.LeakyReLU(0.2,True)
    def forward(s,x):
        x1=s.l(s.conv1(x)); x2=s.l(s.conv2(torch.cat((x,x1),1))); x3=s.l(s.conv3(torch.cat((x,x1,x2),1)))
        x4=s.l(s.conv4(torch.cat((x,x1,x2,x3),1))); x5=s.conv5(torch.cat((x,x1,x2,x3,x4),1)); return x5*0.2+x
class RRDB(nn.Module):
    def __init__(s): super().__init__(); s.rdb1=RDB(); s.rdb2=RDB(); s.rdb3=RDB()
    def forward(s,x): return s.rdb3(s.rdb2(s.rdb1(x)))*0.2+x
class Net(nn.Module):
    def __init__(s, nb=6):
        super().__init__()
        s.conv_first=nn.Conv2d(3,64,3,1,1); s.body=nn.Sequential(*[RRDB() for _ in range(nb)]); s.conv_body=nn.Conv2d(64,64,3,1,1)
        s.conv_up1=nn.Conv2d(64,64,3,1,1); s.conv_up2=nn.Conv2d(64,64,3,1,1); s.conv_hr=nn.Conv2d(64,64,3,1,1); s.conv_last=nn.Conv2d(64,3,3,1,1); s.l=nn.LeakyReLU(0.2,True)
    def forward(s,x):
        f=s.conv_first(x); f=f+s.conv_body(s.body(f))
        f=s.l(s.conv_up1(F.interpolate(f,scale_factor=2,mode='nearest'))); f=s.l(s.conv_up2(F.interpolate(f,scale_factor=2,mode='nearest')))
        return s.conv_last(s.l(s.conv_hr(f)))
net=Net(); sd=torch.load(sys.argv[1],map_location='cpu'); sd=sd.get('params_ema',sd.get('params',sd)); net.load_state_dict(sd,strict=True); net.eval()
def up(im, tile=240, pad=12):
    x=torch.from_numpy(np.asarray(im.convert('RGB')).astype(np.float32)/255).permute(2,0,1)[None]
    _,_,h,w=x.shape; out=torch.zeros(1,3,h*4,w*4)
    with torch.no_grad():
        for y0 in range(0,h,tile):
            for x0 in range(0,w,tile):
                y1,x1=min(y0+tile,h),min(x0+tile,w); ya,xa=max(y0-pad,0),max(x0-pad,0); yb,xb=min(y1+pad,h),min(x1+pad,w)
                o=net(x[:,:,ya:yb,xa:xb]).clamp(0,1)
                out[:,:,y0*4:y1*4,x0*4:x1*4]=o[:,:,(y0-ya)*4:(y1-ya)*4,(x0-xa)*4:(x1-xa)*4]
    return Image.fromarray((out[0].permute(1,2,0).numpy()*255+0.5).astype(np.uint8))
outdir=sys.argv[2]
for f in sys.argv[3:]:
    o=os.path.join(outdir, os.path.splitext(os.path.basename(f))[0][-12:]+'_x4.png')
    if os.path.exists(o): continue
    up(Image.open(f)).save(o); print('done',o,flush=True)
