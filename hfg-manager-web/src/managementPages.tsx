import {useEffect,useMemo,useState} from 'react';
import {Button,Card,Form,Input,Modal,Progress,Select,Space,Switch,Table,Tag,Toast,Typography} from '@douyinfe/semi-ui';
import {IconDelete,IconEdit,IconKey,IconPlus,IconRefresh,IconSearch} from '@douyinfe/semi-icons';
import {VChart} from '@visactor/react-vchart';
import {api,formatBytes,User,users} from './api';
import {Monitoring,SystemPage} from './systemPages';

const {Title,Text}=Typography;
type Row=Record<string,unknown>;

function Header({title,description,actions}:{title:string;description:string;actions?:React.ReactNode}){
 return <div className="page-header"><div><Title heading={4}>{title}</Title><Text type="tertiary">{description}</Text></div><Space wrap>{actions}</Space></div>;
}
function Stat({label,value,note,good}:{label:string;value:string;note:string;good?:boolean}){
 return <Card className="stat"><Text type="tertiary">{label}</Text><strong>{value}</strong><span className={good?'good':''}>{note}</span></Card>;
}
function errorMessage(error:unknown){return error instanceof Error?error.message:String(error)}
function confirmAction(title:string,content:string,action:()=>Promise<void>){
 Modal.confirm({title,content,okText:'确认',cancelText:'取消',onOk:action});
}
const rowSelection=(selected:string[],setSelected:(keys:string[])=>void)=>({
 selectedRowKeys:selected,
 onChange:(keys:(string|number)[]|undefined)=>setSelected((keys||[]).map(String))
});

export function Dashboard({userMode=false}:{userMode?:boolean}){
 const[summary,setSummary]=useState<Record<string,number>>({});
 const[overallHistory,setOverallHistory]=useState<Row[]>([]);
 const[userHistory,setUserHistory]=useState<Row[]>([]);
 const[groups,setGroups]=useState<Row[]>([]);
 const[choices,setChoices]=useState<User[]>([]);
 const[selected,setSelected]=useState('');
 useEffect(()=>{void Promise.all([
  api<Record<string,number>>('/api/v1/dashboard/summary').then(setSummary),
  api<Row[]>('/api/v1/system/service-groups').then(setGroups),
  users.list(0,200,'').then(result=>setChoices(result.items))
 ]).catch(error=>Toast.error(errorMessage(error)))},[]);
 useEffect(()=>{
  const end=new Date(),start=new Date(end.getTime()-24*3600_000);
  void api<Row[]>(`/api/v1/dashboard/history?from=${start.toISOString()}&to=${end.toISOString()}&bucket=hour`).then(setOverallHistory).catch(error=>Toast.error(errorMessage(error)));
  if(userMode){
   const filter=selected?`&userId=${encodeURIComponent(selected)}`:'';
   void api<Row[]>(`/api/v1/dashboard/user-history?from=${start.toISOString()}&to=${end.toISOString()}&bucket=hour${filter}`).then(setUserHistory).catch(error=>Toast.error(errorMessage(error)));
  }
 },[userMode,selected]);
 const overallValues=useMemo(()=>{
  const values=new Map<string,{time:string;upload:number;download:number}>();
  for(const row of overallHistory){
   const time=new Date(String(row.bucket)).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'});
   const item=values.get(time)||{time,upload:0,download:0};
   item[String(row.direction).toLowerCase() as 'upload'|'download']=Number(row.bytes||0)/1024/1024;
   values.set(time,item);
  }
  return[...values.values()];
 },[overallHistory]);
 const overallSpec={type:'line',data:[{id:'overall',values:overallValues}],xField:'time',yField:['upload','download'],legends:{visible:true},axes:[{orient:'left',title:{visible:true,text:'MiB/小时'}}]};
 const userValues=useMemo(()=>userHistory.map(row=>({
  time:new Date(String(row.bucket)).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'}),
  series:`${String(row.username)}-${String(row.direction)==='UPLOAD'?'上传':'下载'}`,
  bytes:Number(row.bytes||0)/1024/1024
 })),[userHistory]);
 const userSpec={type:'line',data:[{id:'users',values:userValues}],xField:'time',yField:'bytes',seriesField:'series',legends:{visible:true},axes:[{orient:'left',title:{visible:true,text:'MiB/小时'}}]};
 return <>
  <Header title={userMode?'用户看板':'运行总览'} description={userMode?'总体业务指标不区分用户；用户指标默认同时展示全部用户，可按用户筛选':'查看 HFG 服务集群、今日传输和存储运行状态'} actions={userMode?<Select style={{width:240}} value={selected} optionList={[{label:'全部用户',value:''},...choices.map(user=>({label:user.username,value:user.id}))]} onChange={value=>setSelected(String(value))}/>:undefined}/>
  <div className="stat-grid"><Stat label="启用服务组" value={String(summary.serviceGroups||0)} note={`心跳正常节点 ${summary.gatewaysUp||0}`} good={Number(summary.gatewaysUp)>0}/><Stat label="今日完成文件" value={Number(summary.completedFiles||0).toLocaleString()} note="总体：上传与下载合计"/><Stat label="今日上传" value={formatBytes(Number(summary.uploadBytes||0))} note="总体：与用户筛选无关"/><Stat label="今日下载" value={formatBytes(Number(summary.downloadBytes||0))} note="总体：与用户筛选无关"/></div>
  <Card title="总体业务指标 · 最近 24 小时传输量" className="chart-card"><VChart spec={overallSpec as never}/></Card>
  {userMode&&<Card title={selected?'所选用户业务指标 · 最近 24 小时':'全部用户业务指标 · 最近 24 小时'} className="chart-card"><VChart spec={userSpec as never}/></Card>}
  {userMode&&selected?<UserUsage userId={selected}/>:!userMode?<Card title="服务组状态"><Table size="small" pagination={false} rowKey="id" columns={[{title:'服务组',dataIndex:'name'},{title:'VIP',dataIndex:'vip'},{title:'HDFS 集群',dataIndex:'hdfs_cluster_id'},{title:'状态',dataIndex:'status',render:(value:string)=><Tag color={value==='ENABLED'?'green':'grey'}>{value}</Tag>}]} dataSource={groups}/></Card>:null}
 </>;
}

function UserUsage({userId}:{userId:string}){
 const[realtime,setRealtime]=useState<Row>({});
 const[directories,setDirectories]=useState<Row[]>([]);
 useEffect(()=>{
  let stopped=false;
  const load=()=>Promise.all([api<Row>(`/api/v1/dashboard/users/${userId}/realtime`),api<Row[]>(`/api/v1/dashboard/users/${userId}/directories`)]).then(([live,items])=>{if(!stopped){setRealtime(live);setDirectories(items)}}).catch(error=>Toast.error(errorMessage(error)));
  void load();const timer=setInterval(()=>void load(),5000);return()=>{stopped=true;clearInterval(timer)};
 },[userId]);
 return <><div className="stat-grid"><Stat label="实时上传连接" value={String(realtime.uploadConnections||0)} note="5 秒刷新" good/><Stat label="实时下载连接" value={String(realtime.downloadConnections||0)} note="5 秒刷新" good/><Stat label="近一分钟上传" value={formatBytes(Number(realtime.uploadBytesLastMinute||0))} note="已完成字节"/><Stat label="近一分钟下载" value={formatBytes(Number(realtime.downloadBytesLastMinute||0))} note="已完成字节"/></div><Card title="归属目录使用和配额"><Table rowKey="virtualPath" pagination={false} dataSource={directories} columns={[{title:'目录',dataIndex:'name'},{title:'虚拟路径',dataIndex:'virtualPath'},{title:'文件数使用',render:(_:unknown,row:Row)=>row.available?`${Number(row.namespaceUsed||0).toLocaleString()} / ${Number(row.namespaceQuota||-1)<0?'不限':Number(row.namespaceQuota).toLocaleString()}`:<Tag color="red">不可用</Tag>},{title:'空间配额/使用率',render:(_:unknown,row:Row)=>{const quota=Number(row.spaceQuotaBytes??-1),used=Number(row.spaceUsedBytes||0);return quota>0?<div style={{minWidth:180}}><Progress percent={Math.min(100,used/quota*100)} showInfo/><Text type="tertiary">{formatBytes(used)} / {formatBytes(quota)}</Text></div>:<Text>{formatBytes(used)}（无限制）</Text>}}]}/></Card></>;
}

export function Users(){
 const[data,setData]=useState<User[]>([]),[total,setTotal]=useState(0),[page,setPage]=useState(0),[query,setQuery]=useState(''),[loading,setLoading]=useState(false),[selected,setSelected]=useState<string[]>([]);
 const[open,setOpen]=useState(false),[editing,setEditing]=useState<User>(),[passwordUser,setPasswordUser]=useState<User>(),[keyUser,setKeyUser]=useState<User>(),[groups,setGroups]=useState<Row[]>([]);
 async function load(){setLoading(true);try{const result=await users.list(page,20,query);setData(result.items);setTotal(result.total);setSelected([])}catch(error){Toast.error(errorMessage(error))}finally{setLoading(false)}}
 useEffect(()=>{void load()},[page]);
 useEffect(()=>{void api<Row[]>('/api/v1/system/service-groups').then(setGroups).catch(error=>Toast.error(errorMessage(error)))},[]);
 const one=data.find(user=>user.id===selected[0]);
 async function save(values:Record<string,unknown>){try{if(editing)await users.update(editing,values);else await users.create(values);setOpen(false);setEditing(undefined);Toast.success(editing?'用户已更新':'用户已创建');await load()}catch(error){Toast.error(errorMessage(error))}}
 async function bulkStatus(status:'ENABLED'|'DISABLED'){try{for(const id of selected){const user=data.find(item=>item.id===id);if(user)await users.status(user,status)}Toast.success(status==='ENABLED'?'用户已启用':'用户已停用');await load()}catch(error){Toast.error(errorMessage(error))}}
 async function bulkDelete(){try{for(const id of selected)await users.remove(id);Toast.success('用户已删除');await load()}catch(error){Toast.error(errorMessage(error));await load()}}
 const columns=[{title:'用户名',dataIndex:'username'},{title:'部门',dataIndex:'department'},{title:'业务域',dataIndex:'businessDomain'},{title:'服务组',dataIndex:'serviceGroupId'},{title:'状态',dataIndex:'status',render:(value:string)=><Tag color={value==='ENABLED'?'green':'grey'}>{value}</Tag>},{title:'更新时间',dataIndex:'updatedAt',render:(value:string)=>new Date(value).toLocaleString()}];
 const initial=editing?{department:editing.department,businessDomain:editing.businessDomain,phone:editing.phone,email:editing.email,note:editing.note,serviceGroupId:editing.serviceGroupId,expiresAt:editing.expiresAt}:{};
 return <>
  <Header title="用户管理" description="勾选用户后在顶部统一执行编辑、认证信息和批量状态操作" actions={<><Input prefix={<IconSearch/>} value={query} onChange={setQuery} onEnterPress={()=>{setPage(0);void load()}} placeholder="用户名/部门/业务域"/><Button icon={<IconRefresh/>} onClick={load}/><Button theme="solid" icon={<IconPlus/>} onClick={()=>{setEditing(undefined);setOpen(true)}}>添加</Button><Button icon={<IconEdit/>} disabled={selected.length!==1} onClick={()=>{setEditing(one);setOpen(true)}}>编辑</Button><Button disabled={selected.length!==1} onClick={()=>setPasswordUser(one)}>重置密码</Button><Button icon={<IconKey/>} disabled={selected.length!==1} onClick={()=>setKeyUser(one)}>SSH 公钥</Button><Button disabled={!selected.length} onClick={()=>void bulkStatus('ENABLED')}>启用</Button><Button disabled={!selected.length} onClick={()=>void bulkStatus('DISABLED')}>停用</Button><Button type="danger" icon={<IconDelete/>} disabled={!selected.length} onClick={()=>confirmAction('删除所选用户',`将删除 ${selected.length} 个用户；存在归属目录的用户必须先删除或转移目录。`,bulkDelete)}>删除</Button></>}/>
  <Card><Table rowKey="id" rowSelection={rowSelection(selected,setSelected)} loading={loading} columns={columns} dataSource={data} pagination={{currentPage:page+1,pageSize:20,total,onPageChange:value=>setPage(value-1),showTotal:true}}/></Card>
  <Modal title={editing?'编辑用户':'添加用户'} visible={open} onCancel={()=>{setOpen(false);setEditing(undefined)}} footer={null}><Form key={editing?.id||'create'} initValues={initial} onSubmit={save} labelPosition="top">{!editing&&<><Form.Input field="username" label="用户名" rules={[{required:true}]}/><Form.Input field="password" label="初始密码" mode="password" rules={[{required:true},{min:12,message:'至少 12 位'}]}/></>}<div className="form-grid"><Form.Input field="department" label="部门"/><Form.Input field="businessDomain" label="业务域"/><Form.Input field="phone" label="电话"/><Form.Input field="email" label="邮箱"/><Form.Select field="serviceGroupId" label="服务组" optionList={groups.map(group=>({label:String(group.name),value:String(group.id)}))} rules={[{required:true}]}/></div><Form.TextArea field="note" label="备注"/><Button htmlType="submit" theme="solid" block>{editing?'保存':'创建'}</Button></Form></Modal>
  <Modal title={`重置 ${passwordUser?.username||''} 的密码`} visible={Boolean(passwordUser)} footer={null} onCancel={()=>setPasswordUser(undefined)}><Form labelPosition="top" onSubmit={async values=>{try{await users.resetPassword(passwordUser!,String(values.password));setPasswordUser(undefined);Toast.success('密码已重置');await load()}catch(error){Toast.error(errorMessage(error))}}}><Form.Input field="password" label="新密码" mode="password" rules={[{required:true},{min:12,message:'至少 12 位'}]}/><Button htmlType="submit" theme="solid" block>确认重置</Button></Form></Modal>
  {keyUser&&<SshKeys user={keyUser} onClose={()=>setKeyUser(undefined)}/>}
 </>;
}

function SshKeys({user,onClose}:{user:User;onClose:()=>void}){
 const[keys,setKeys]=useState<Row[]>([]),[selected,setSelected]=useState<string[]>([]);
 async function load(){setKeys(await api<Row[]>(`/api/v1/users/${user.id}/ssh-keys`));setSelected([])}
 useEffect(()=>{void load().catch(error=>Toast.error(errorMessage(error)))},[user.id]);
 async function removeKeys(){for(const id of selected)await api(`/api/v1/users/${user.id}/ssh-keys/${id}`,{method:'DELETE'});await load()}
 return <Modal title={`${user.username} 的 SSH 公钥`} visible footer={null} width={760} onCancel={onClose}><Space style={{marginBottom:12}}><Button type="danger" disabled={!selected.length} onClick={()=>confirmAction('删除所选公钥',`将删除 ${selected.length} 个公钥。`,removeKeys)}>删除所选</Button></Space><Form labelPosition="top" onSubmit={async values=>{try{await api(`/api/v1/users/${user.id}/ssh-keys`,{method:'POST',body:JSON.stringify(values)});Toast.success('SSH 公钥已添加');await load()}catch(error){Toast.error(errorMessage(error))}}}><Form.TextArea field="publicKey" label="OpenSSH 公钥" placeholder="ssh-ed25519 AAAA... user@host" rules={[{required:true}]}/><Form.Input field="note" label="备注"/><Button htmlType="submit" theme="solid">添加公钥</Button></Form><Table rowKey="id" rowSelection={rowSelection(selected,setSelected)} pagination={false} dataSource={keys} columns={[{title:'SHA-256 指纹',dataIndex:'fingerprint'},{title:'备注',dataIndex:'note',render:(value:string)=>value||'-'},{title:'添加时间',dataIndex:'created_at',render:(value:string)=>new Date(value).toLocaleString()}]}/></Modal>;
}

export function Directories(){
 const[data,setData]=useState<Row[]>([]),[userList,setUserList]=useState<User[]>([]),[clusters,setClusters]=useState<Row[]>([]),[selected,setSelected]=useState<string[]>([]),[query,setQuery]=useState(''),[userFilter,setUserFilter]=useState(''),[open,setOpen]=useState(false),[editing,setEditing]=useState<Row>();
 async function load(){try{const params=new URLSearchParams();if(query)params.set('query',query);if(userFilter)params.set('userId',userFilter);const[directories,userPage,hdfsClusters]=await Promise.all([api<Row[]>(`/api/v1/directories?${params}`),users.list(0,200,''),api<Row[]>('/api/v1/system/hdfs-clusters')]);setData(directories);setUserList(userPage.items);setClusters(hdfsClusters);setSelected([])}catch(error){Toast.error(errorMessage(error))}}
 useEffect(()=>{void load()},[userFilter]);
 const one=data.find(row=>String(row.id)===selected[0]);
 async function save(values:Record<string,unknown>){try{const path=editing?`/api/v1/directories/${editing.id}`:'/api/v1/directories';await api(path,{method:editing?'PUT':'POST',body:JSON.stringify(values)});setOpen(false);setEditing(undefined);Toast.success('目录配置已保存');await load()}catch(error){Toast.error(errorMessage(error))}}
 async function provisionSelected(){try{for(const id of selected)await api(`/api/v1/directories/${id}/provision`,{method:'POST'});Toast.success('所选目录已重新配置');await load()}catch(error){Toast.error(errorMessage(error));await load()}}
 async function deleteSelected(){try{for(const id of selected)await api(`/api/v1/directories/${id}`,{method:'DELETE'});Toast.success('所选目录已删除');await load()}catch(error){Toast.error(errorMessage(error));await load()}}
 const initial=editing?{name:editing.name,virtualPath:editing.virtual_path,hdfsPath:editing.hdfs_path,hdfsClusterId:editing.hdfs_cluster_id,userId:editing.owner_user_id,accessMode:editing.access_mode,fileQuota:Number(editing.namespace_quota??-1),spaceQuotaBytes:Number(editing.space_quota_bytes??-1),autoCreate:Boolean(editing.auto_create)}:{virtualPath:'/',accessMode:'READ_WRITE',fileQuota:-1,spaceQuotaBytes:-1,autoCreate:true};
 return <>
  <Header title="目录管理" description="每个目录仅归属一个用户；权限、文件数配额和空间配额均在目录上配置" actions={<><Input prefix={<IconSearch/>} value={query} onChange={setQuery} onEnterPress={load} placeholder="目录名/路径/用户名"/><Select style={{width:200}} value={userFilter} optionList={[{label:'全部用户',value:''},...userList.map(user=>({label:user.username,value:user.id}))]} onChange={value=>setUserFilter(String(value))}/><Button icon={<IconRefresh/>} onClick={load}/><Button theme="solid" icon={<IconPlus/>} onClick={()=>{setEditing(undefined);setOpen(true)}}>添加</Button><Button icon={<IconEdit/>} disabled={selected.length!==1} onClick={()=>{setEditing(one);setOpen(true)}}>编辑</Button><Button disabled={!selected.length} onClick={()=>void provisionSelected()}>重新配置</Button><Button type="danger" icon={<IconDelete/>} disabled={!selected.length} onClick={()=>confirmAction('删除所选目录',`将删除 ${selected.length} 条虚拟目录映射，不删除 HDFS 中的实际数据。`,deleteSelected)}>删除</Button></>}/>
  <Card><Table rowKey="id" rowSelection={rowSelection(selected,setSelected)} dataSource={data} columns={[{title:'名称',dataIndex:'name'},{title:'归属用户',dataIndex:'owner_username',render:(value:string)=>value||<Tag color="red">待分配</Tag>},{title:'权限',dataIndex:'access_mode',render:(value:string)=><Tag color={value==='READ_WRITE'?'green':'blue'}>{value==='READ_WRITE'?'读写':'只读'}</Tag>},{title:'虚拟路径',dataIndex:'virtual_path'},{title:'HDFS 路径',dataIndex:'hdfs_path'},{title:'文件数配额',dataIndex:'namespace_quota',render:(value:number)=>value<0?'无限制':Number(value).toLocaleString()},{title:'空间配额',dataIndex:'space_quota_bytes',render:(value:number)=>value<0?'无限制':formatBytes(value)},{title:'配置状态',dataIndex:'provisioning_status',render:(value:string)=><Tag color={value==='READY'?'green':value==='FAILED'?'red':'amber'}>{value}</Tag>},{title:'错误',dataIndex:'provisioning_error',render:(value:string)=>value||'-'}]}/></Card>
  <Modal title={editing?'编辑目录':'添加目录'} visible={open} footer={null} onCancel={()=>{setOpen(false);setEditing(undefined)}}><Form key={String(editing?.id||'create')} initValues={initial} labelPosition="top" onSubmit={save}><Form.Input field="name" label="名称" rules={[{required:true}]}/><Form.Input field="virtualPath" label="虚拟路径" rules={[{required:true}]}/><Form.Input field="hdfsPath" label="HDFS 路径" rules={[{required:true}]}/><Form.Select field="hdfsClusterId" label="HDFS 集群" optionList={clusters.map(cluster=>({label:String(cluster.name),value:String(cluster.id)}))} rules={[{required:true}]}/><div className="form-grid"><Form.Select field="userId" label="归属用户" optionList={userList.map(user=>({label:user.username,value:user.id}))} rules={[{required:true}]}/><Form.Select field="accessMode" label="归属用户权限" optionList={[{label:'只读',value:'READ_ONLY'},{label:'读写',value:'READ_WRITE'}]} rules={[{required:true}]}/></div><div className="form-grid"><Form.InputNumber field="fileQuota" label="文件数配额（-1 不限）"/><Form.InputNumber field="spaceQuotaBytes" label="空间配额字节（-1 不限）"/></div><Form.Switch field="autoCreate" label="自动创建并配置 HDFS 目录"/><Button htmlType="submit" theme="solid" block>保存</Button></Form></Modal>
 </>;
}

export function Traffic({dashboard=false}:{dashboard?:boolean}){
 const[data,setData]=useState<Row[]>([]),[history,setHistory]=useState<Row[]>([]),[selected,setSelected]=useState<string[]>([]),[editing,setEditing]=useState<Row>();
 async function load(){try{const current=api<Row[]>('/api/v1/dashboard/flow-control');if(dashboard){const to=new Date(),from=new Date(to.getTime()-24*3600_000);const[rows,points]=await Promise.all([current,api<Row[]>(`/api/v1/dashboard/flow-control/history?from=${from.toISOString()}&to=${to.toISOString()}`)]);setData(rows);setHistory(points)}else setData(await current);setSelected([])}catch(error){Toast.error(errorMessage(error))}}
 useEffect(()=>{void load()},[]);
 const one=data.find(row=>String(row.user_id)===selected[0]);
 const rateValues=useMemo(()=>history.map(row=>({time:new Date(String(row.bucket)).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'}),series:`${String(row.username)}-${String(row.direction)==='UPLOAD'?'上传':'下载'}`,rate:Number(row.bytes||0)/60/1024/1024})),[history]);
 const rateSpec={type:'line',data:[{id:'rates',values:rateValues}],xField:'time',yField:'rate',seriesField:'series',legends:{visible:true},axes:[{orient:'left',title:{visible:true,text:'MiB/s'}}]};
 const columns=[{title:'用户',dataIndex:'username'},{title:'上传限速',dataIndex:'upload_bytes_per_second',render:(value:number)=>value?`${formatBytes(value)}/s`:'不限'},{title:'下载限速',dataIndex:'download_bytes_per_second',render:(value:number)=>value?`${formatBytes(value)}/s`:'不限'},{title:'并发连接数',dataIndex:'max_connections',render:(value:number)=>value||'不限'},...(dashboard?[{title:'近一分钟上传',dataIndex:'upload_recent_bytes_per_second',render:(value:number)=>`${formatBytes(value||0)}/s`},{title:'近一分钟下载',dataIndex:'download_recent_bytes_per_second',render:(value:number)=>`${formatBytes(value||0)}/s`},{title:'状态',render:(_:unknown,row:Row)=><Space>{Boolean(row.upload_rate_limit_reached)&&<Tag color="amber">上传达限</Tag>}{Boolean(row.download_rate_limit_reached)&&<Tag color="amber">下载达限</Tag>}{!row.upload_rate_limit_reached&&!row.download_rate_limit_reached&&<Tag color="green">正常</Tag>}</Space>}]:[])];
 return <>
  <Header title={dashboard?'流控看板':'流控管理'} description={dashboard?'按用户查看上传/下载实际速率和限速状态':'流控策略仅包含上传速率、下载速率和并发连接数'} actions={!dashboard?<><Button icon={<IconRefresh/>} onClick={load}/><Button theme="solid" icon={<IconEdit/>} disabled={selected.length!==1} onClick={()=>setEditing(one)}>编辑策略</Button></>:<Button icon={<IconRefresh/>} onClick={load}/>} />
  <Card title={dashboard?'当前流控状态':undefined}><Table rowKey="user_id" rowSelection={!dashboard?rowSelection(selected,setSelected):undefined} dataSource={data} columns={columns}/></Card>
  {dashboard&&<Card title="最近 24 小时用户实际传输速率" className="chart-card section-card"><VChart spec={rateSpec as never}/></Card>}
  {!dashboard&&<Modal title={`${editing?.username||''} 流控策略`} visible={Boolean(editing)} footer={null} onCancel={()=>setEditing(undefined)}><Form key={String(editing?.user_id)} initValues={{uploadBytesPerSecond:Number(editing?.upload_bytes_per_second||0),downloadBytesPerSecond:Number(editing?.download_bytes_per_second||0),maxConnections:Number(editing?.max_connections||0)}} labelPosition="top" onSubmit={async values=>{try{await api(`/api/v1/users/${editing?.user_id}/traffic-policy`,{method:'PUT',body:JSON.stringify(values)});setEditing(undefined);Toast.success('流控策略已保存');await load()}catch(error){Toast.error(errorMessage(error))}}}><Form.InputNumber field="uploadBytesPerSecond" label="上传速率 B/s（0 不限）"/><Form.InputNumber field="downloadBytesPerSecond" label="下载速率 B/s（0 不限）"/><Form.InputNumber field="maxConnections" label="并发连接数（0 不限）"/><Button htmlType="submit" theme="solid" block>保存策略</Button></Form></Modal>}
 </>;
}

export function SystemManagement(){
 const[enabled,setEnabled]=useState(true),[loaded,setLoaded]=useState(false);
 useEffect(()=>{void api<{monitoringPanelEnabled:boolean}>('/api/v1/system/settings').then(settings=>{setEnabled(settings.monitoringPanelEnabled);setLoaded(true)}).catch(error=>Toast.error(errorMessage(error)))},[]);
 async function change(value:boolean){try{await api('/api/v1/system/settings',{method:'PUT',body:JSON.stringify({monitoringPanelEnabled:value})});setEnabled(value);window.dispatchEvent(new Event('hfg-settings-changed'));Toast.success(value?'监控告警面板已显示':'监控告警面板已隐藏')}catch(error){Toast.error(errorMessage(error))}}
 return <><SystemPage/><Card title="管理界面功能开关" className="section-card"><Space><Switch checked={enabled} loading={!loaded} onChange={value=>void change(value)}/><Text>显示“监控告警”面板</Text><Text type="tertiary">关闭后仅隐藏管理端入口，不停止 Prometheus 指标采集。</Text></Space></Card></>;
}

export {Monitoring};
