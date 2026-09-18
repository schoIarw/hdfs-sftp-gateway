import {semiGlobal} from '@douyinfe/semi-ui';
import type {createRoot as createRootType} from 'react-dom/client';

/**
 * Semi UI 的静态 API（Toast、Modal.confirm/info/error、Notification）通过 reactRender 渲染到独立容器。
 * React 19 移除了 react-dom 上的 legacy render/createRoot，必须显式注入 createRoot，
 * 否则这些调用会静默失败：点击删除既没有确认框，也没有任何错误提示。
 */
export function installSemiReact19(createRoot: typeof createRootType) {
  semiGlobal.config.createRoot = createRoot as never;
}
