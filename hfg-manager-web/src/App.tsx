import {createContext,useContext,useEffect,useState} from 'react';
import {Navigate,Route,Routes,useLocation,useNavigate} from 'react-router-dom';
import {Avatar,Button,Layout,Nav,Typography} from '@douyinfe/semi-ui';
import {IconActivity,IconAlarm,IconBriefcase,IconFolder,IconHistogram,IconHome,IconSetting,IconUser} from '@douyinfe/semi-icons';
import {api,authenticate,clearCredentials,hasCredentials} from './api';
import {Dashboard,Directories,Monitoring,SystemManagement,Traffic,Users} from './managementPages';

const {Header,Sider,Content}=Layout;
const menu=[
 ['/','总览',<IconHome/>],
 ['/users','用户管理',<IconUser/>],
 ['/directories','目录管理',<IconFolder/>],
 ['/traffic','流控管理',<IconHistogram/>],
 ['/user-dashboard','用户看板',<IconActivity/>],
 ['/flow-dashboard','流控看板',<IconBriefcase/>],
 ['/monitoring','监控告警',<IconAlarm/>],
 ['/system','系统管理',<IconSetting/>]
] as const;

type AuthContextValue={authenticated:boolean;username:string;login:(username:string,password:string)=>Promise<void>;logout:()=>void};
const AuthContext=createContext<AuthContextValue|undefined>(undefined);
function useAuth(){const value=useContext(AuthContext);if(!value)throw new Error('AuthContext is not available');return value}

function Shell(){
 const location=useLocation(),navigate=useNavigate(),auth=useAuth();
 const[monitoringEnabled,setMonitoringEnabled]=useState(true);
 useEffect(()=>{
  const load=()=>void api<{monitoringPanelEnabled:boolean}>('/api/v1/system/settings').then(settings=>setMonitoringEnabled(settings.monitoringPanelEnabled)).catch(()=>setMonitoringEnabled(true));
  load();window.addEventListener('hfg-settings-changed',load);return()=>window.removeEventListener('hfg-settings-changed',load);
 },[]);
 const visibleMenu=menu.filter(([path])=>path!=='/monitoring'||monitoringEnabled);
 return <Layout className="app-shell"><Header className="topbar"><div className="brand"><span className="brand-mark">H</span><span>HFG 管理平台</span></div><div className="top-actions"><Avatar size="small" color="blue">A</Avatar><span>{auth.username||'管理员'}</span><Button theme="borderless" onClick={()=>{auth.logout();navigate('/login',{replace:true})}}>退出</Button></div></Header><Layout><Sider className="sidebar"><Nav selectedKeys={[location.pathname]} items={visibleMenu.map(([itemKey,text,icon])=>({itemKey,text,icon}))} onSelect={item=>navigate(String(item.itemKey))} footer={{collapseButton:true}}/></Sider><Content className="content"><Routes><Route path="/" element={<Dashboard/>}/><Route path="/users" element={<Users/>}/><Route path="/directories" element={<Directories/>}/><Route path="/traffic" element={<Traffic/>}/><Route path="/user-dashboard" element={<Dashboard userMode/>}/><Route path="/flow-dashboard" element={<Traffic dashboard/>}/><Route path="/monitoring" element={monitoringEnabled?<Monitoring/>:<Navigate to="/system" replace/>}/><Route path="/system" element={<SystemManagement/>}/><Route path="*" element={<Navigate to="/" replace/>}/></Routes></Content></Layout></Layout>;
}

export function App(){
 const[authenticated,setAuthenticated]=useState(hasCredentials),[username,setUsername]=useState('');
 const auth:AuthContextValue={authenticated,username,login:async(user,password)=>{const admin=await authenticate(user,password);setUsername(admin.username);setAuthenticated(true)},logout:()=>{clearCredentials();setUsername('');setAuthenticated(false)}};
 return <AuthContext.Provider value={auth}><Routes><Route path="/login" element={authenticated?<Navigate to="/" replace/>:<Login/>}/><Route path="/*" element={authenticated?<Shell/>:<Navigate to="/login" replace/>}/></Routes></AuthContext.Provider>;
}

function Login(){
 const navigate=useNavigate(),auth=useAuth();
 const[user,setUser]=useState('admin'),[password,setPassword]=useState(''),[error,setError]=useState(''),[loading,setLoading]=useState(false);
 async function submit(event:React.FormEvent){event.preventDefault();setLoading(true);setError('');try{await auth.login(user,password);navigate('/',{replace:true})}catch(failure){clearCredentials();setError(failure instanceof Error?failure.message:'登录失败')}finally{setLoading(false)}}
 return <div className="login"><form className="login-card" onSubmit={submit}><div className="login-brand"><span className="brand-mark large">H</span><div><Typography.Title heading={3}>HFG 管理平台</Typography.Title><Typography.Text type="tertiary">HDFS FTP/SFTP Gateway</Typography.Text></div></div><label>用户名<input value={user} onChange={event=>setUser(event.target.value)} autoComplete="username"/></label><label>密码<input type="password" value={password} onChange={event=>setPassword(event.target.value)} autoComplete="current-password"/></label>{error&&<div className="error">{error}</div>}<Button htmlType="submit" theme="solid" type="primary" block loading={loading}>登录</Button></form></div>;
}
