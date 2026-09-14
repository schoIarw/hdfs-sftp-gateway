import{describe,expect,it}from'vitest';import{formatBytes}from'./api';
describe('formatBytes',()=>{it('uses binary units',()=>{expect(formatBytes(0)).toBe('0 B');expect(formatBytes(1024)).toBe('1.00 KiB');expect(formatBytes(1024**4)).toBe('1.00 TiB')});it('rejects invalid values',()=>expect(formatBytes(-1)).toBe('-'))});
