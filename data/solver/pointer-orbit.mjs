/* Pure pointer gesture state shared by mouse, touch and pen in the mobile viewer. */
export function createPointerOrbit({rotationScale=.008,dragThreshold=6,minPhi=.28,maxPhi=2.82,minDistance=5.9,maxDistance=12}={}) {
  const pointers=new Map();
  let drag=null,pinch=null,blockedPaint=false,moved=false;
  const clamp=(value,min,max)=>Math.max(min,Math.min(max,value));
  const first=()=>pointers.entries().next().value;
  function seedDrag(camera) {
    const entry=first();drag=entry?{id:entry[0],x:entry[1].x,y:entry[1].y,theta:camera.theta,phi:camera.phi}:null;
    pinch=null;moved=blockedPaint;
  }
  function span() {const [a,b]=[...pointers.values()];return Math.max(1,Math.hypot(a.x-b.x,a.y-b.y));}
  function seedPinch(camera) {pinch={span:span(),distance:camera.distance};drag=null;moved=true;blockedPaint=true;}
  function begin(id,x,y,camera) {
    if(pointers.has(id))return false;
    if(!pointers.size){blockedPaint=false;moved=false;}
    pointers.set(id,{x,y});
    if(pointers.size===1)seedDrag(camera);else seedPinch(camera);
    return true;
  }
  function move(id,x,y,camera) {
    if(!pointers.has(id))return null;pointers.set(id,{x,y});
    if(pointers.size>=2){if(!pinch)seedPinch(camera);return {distance:clamp(pinch.distance*pinch.span/span(),minDistance,maxDistance)};}
    if(!drag || drag.id!==id)seedDrag(camera);
    const dx=x-drag.x,dy=y-drag.y;
    if(Math.abs(dx)+Math.abs(dy)>dragThreshold)moved=true;
    if(!moved)return null;
    return {theta:drag.theta-dx*rotationScale,phi:clamp(drag.phi-dy*rotationScale,minPhi,maxPhi)};
  }
  function end(id,camera) {
    if(!pointers.has(id))return {paint:false,remaining:pointers.size};
    const paint=pointers.size===1 && drag?.id===id && !blockedPaint && !moved;
    pointers.delete(id);
    if(pointers.size>=2)seedPinch(camera);
    else if(pointers.size===1)seedDrag(camera);
    else{drag=null;pinch=null;blockedPaint=false;moved=false;}
    return {paint,remaining:pointers.size};
  }
  function cancel(id,camera) {if(!pointers.has(id))return {paint:false,remaining:pointers.size};blockedPaint=true;return {...end(id,camera),paint:false};}
  return {begin,move,end,cancel,has:id=>pointers.has(id),get size(){return pointers.size;},clear(){pointers.clear();drag=null;pinch=null;blockedPaint=false;moved=false;}};
}
