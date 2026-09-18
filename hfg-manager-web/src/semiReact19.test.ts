// @vitest-environment jsdom
import{Modal,Toast}from'@douyinfe/semi-ui';import{createRoot}from'react-dom/client';import{beforeEach,describe,expect,it}from'vitest';import{installSemiReact19}from'./semiReact19';

const tick=()=>new Promise(resolve=>setTimeout(resolve,120));

describe('Semi 静态 API 在 React 19 下的可用性',()=>{
 beforeEach(()=>{document.body.innerHTML='';installSemiReact19(createRoot)});

 it('未注入 createRoot 时确认框不会渲染（回归保护）',async()=>{
  const{semiGlobal}=await import('@douyinfe/semi-ui');
  const injected=semiGlobal.config.createRoot;
  semiGlobal.config.createRoot=undefined;
  Modal.confirm({title:'确认删除',content:'未注入',onOk:()=>{}});
  await tick();
  expect(document.querySelector('.semi-modal')).toBeNull();
  semiGlobal.config.createRoot=injected;
 });

 it('注入 createRoot 后 Modal.confirm 弹出确认框',async()=>{
  Modal.confirm({title:'确认删除',content:'HDFS 连接 hadoop01',okText:'删除',onOk:()=>{}});
  await tick();
  const modal=document.querySelector('.semi-modal');
  expect(modal).not.toBeNull();
  expect(modal?.textContent).toContain('确认删除');
  expect(modal?.textContent).toContain('HDFS 连接 hadoop01');
 });

 it('注入 createRoot 后 Modal.error 弹出阻止原因',async()=>{
  Modal.error({title:'无法删除 HDFS 连接',content:'HDFS 连接 “hadoop01” 仍有关联，无法删除'});
  await tick();
  const modal=document.querySelector('.semi-modal');
  expect(modal?.textContent).toContain('无法删除 HDFS 连接');
  expect(modal?.textContent).toContain('仍有关联');
 });

 it('注入 createRoot 后 Toast 能显示提示',async()=>{
  Toast.error('无法删除服务组');
  await tick();
  expect(document.querySelector('.semi-toast')).not.toBeNull();
  expect(document.body.textContent).toContain('无法删除服务组');
 });
});
