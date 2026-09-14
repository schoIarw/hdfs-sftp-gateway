import{describe,expect,it}from'vitest';
import{snapshotVersion}from'./api';

describe('snapshotVersion',()=>{
 it('reads the signed payload version',()=>expect(snapshotVersion({payloadJson:'{"version":7}'})).toBe(7));
 it('returns zero for an invalid envelope',()=>expect(snapshotVersion({payloadJson:'invalid'})).toBe(0));
});
