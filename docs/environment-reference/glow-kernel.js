/* Platform-independent visual reference. Bearings are head-relative horizontal angles. */
globalThis.SoundGlowKernel = (() => {
  const TAU=Math.PI*2;
  function angularDistance(a,b){let d=(a-b)%TAU;if(d>Math.PI)d-=TAU;if(d<-Math.PI)d+=TAU;return Math.abs(d);}
  function sample(x,y,w,h,bearingDeg,p,allEdges=false){
    const distance=Math.max(0,Math.min(x,y,w-x,h-y));
    const nx=(x-w/2)/(w/2),ny=(y-h/2)/(h/2);
    const angle=Math.atan2(nx,-ny);
    const sigma=(p.spreadDeg*Math.PI/180)/2.355;
    const delta=angularDistance(angle,bearingDeg*Math.PI/180);
    const sector=allEdges?1:Math.exp(-.5*(delta/sigma)**2);
    const core=.65*Math.exp(-distance/2.2);
    const ribbon=.35*Math.exp(-distance/18);
    const bloom=.35*Math.exp(-.5*(distance/p.depthPx)**2);
    const alpha=Math.min(1,(core+ribbon+bloom)*sector*p.intensity);
    const near=Math.exp(-distance/13);
    return [Math.round(near*48),Math.round(114+near*86),255,alpha];
  }
  function opacityAt(elapsedMs,hold,reduced=false){
    if(hold)return 1;
    if(elapsedMs<0)return 0;
    if(reduced)return elapsedMs<2600?1:0;
    if(elapsedMs<180){const t=elapsedMs/180;return t*t*(3-2*t);}
    if(elapsedMs<1200)return 1;
    if(elapsedMs<2800){const t=(elapsedMs-1200)/1600;return 1-t*t*(3-2*t);}
    return 0;
  }
  function nearestDirection(bearingDeg){return ((Math.round(bearingDeg/45)%8)+8)%8;}
  function pointerBearing(x,y,w,h){return ((Math.atan2((x-w/2)/(w/2),-(y-h/2)/(h/2))*180/Math.PI)%360+360)%360;}
  return {sample,opacityAt,nearestDirection,pointerBearing};
})();
