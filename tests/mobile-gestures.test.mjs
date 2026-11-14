import assert from 'node:assert/strict';
import {createPointerOrbit} from '../data/solver/pointer-orbit.mjs';
const camera={theta:.72,phi:.94,distance:8.8};
let checks=0;
function test(name,run){run();checks++;console.log('✓ '+name);}
test('single unmoved pointer paints once',()=>{const g=createPointerOrbit();g.begin(1,50,50,camera);assert.equal(g.move(1,52,51,camera),null);assert.equal(g.end(1,camera).paint,true);assert.equal(g.end(1,camera).paint,false);});
test('mouse and one-finger drag preserve existing sensitivity and do not paint',()=>{const g=createPointerOrbit();g.begin(1,50,50,camera);assert.deepEqual(g.move(1,80,65,camera),{theta:.72-30*.008,phi:.94-15*.008});assert.equal(g.end(1,camera).paint,false);});
test('pinch out zooms in and pinch in zooms out',()=>{const g=createPointerOrbit();g.begin(1,0,0,camera);g.begin(2,100,0,camera);assert.equal(g.move(2,140,0,camera).distance,8.8/1.4);assert.equal(g.move(2,70,0,camera).distance,12);assert.equal(g.end(2,camera).paint,false);assert.equal(g.end(1,camera).paint,false);});
test('two stationary fingers never paint on either release',()=>{const g=createPointerOrbit();g.begin(1,10,10,camera);g.begin(2,20,20,camera);assert.equal(g.end(1,camera).paint,false);assert.equal(g.end(2,camera).paint,false);});
test('lifting one pinch finger rebases orbit without painting or jumping',()=>{const g=createPointerOrbit();g.begin(1,10,10,camera);g.begin(2,110,10,camera);const zoom=g.move(2,150,10,camera);const now={...camera,...zoom};g.end(2,now);assert.deepEqual(g.move(1,20,20,now),{theta:.72-10*.008,phi:.94-10*.008});assert.equal(g.end(1,now).paint,false);});
test('cancellation and lost capture cannot paint',()=>{const g=createPointerOrbit();g.begin(1,50,50,camera);assert.equal(g.cancel(1,camera).paint,false);assert.equal(g.end(1,camera).paint,false);});
test('cancelling either finger suppresses the remaining tap',()=>{const g=createPointerOrbit();g.begin(1,0,0,camera);g.begin(2,50,0,camera);g.cancel(2,camera);assert.equal(g.end(1,camera).paint,false);});
test('new independent gesture after pinch can paint again',()=>{const g=createPointerOrbit();g.begin(1,0,0,camera);g.begin(2,50,0,camera);g.end(1,camera);g.end(2,camera);g.begin(3,20,20,camera);assert.equal(g.end(3,camera).paint,true);});
test('pinch limits and orbit vertical limits remain bounded',()=>{const g=createPointerOrbit();g.begin(1,0,0,camera);g.begin(2,100,0,camera);assert.equal(g.move(2,10000,0,camera).distance,5.9);assert.equal(g.move(2,1,0,camera).distance,12);g.clear();g.begin(3,0,0,camera);assert.equal(g.move(3,0,10000,camera).phi,.28);assert.equal(g.move(3,0,-10000,camera).phi,2.82);});
test('third pointer transitions and clear do not leak stale interactions',()=>{const g=createPointerOrbit();g.begin(1,0,0,camera);g.begin(2,100,0,camera);g.begin(3,200,0,camera);g.end(1,camera);assert.ok(Number.isFinite(g.move(3,220,0,camera).distance));g.clear();assert.equal(g.size,0);assert.equal(g.move(2,1,1,camera),null);assert.equal(g.end(3,camera).paint,false);});
console.log(`Mobile gestures: ${checks} checks passed.`);
