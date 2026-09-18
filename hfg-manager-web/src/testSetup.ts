// jsdom 没有 canvas 实现，Semi 的 Toast 间接引入 lottie-web，会在导入阶段访问 2D 上下文。
// 这里提供一个最小可用的桩，保证弹框/提示相关组件可以在 Node 环境里完成渲染断言。
const context = {
  fillStyle: '',
  strokeStyle: '',
  globalAlpha: 1,
  globalCompositeOperation: '',
  font: '',
  textAlign: '',
  textBaseline: '',
  lineWidth: 1,
  canvas: {},
  createLinearGradient: () => ({addColorStop() {}}),
  createPattern: () => null,
  measureText: () => ({width: 0}),
  getImageData: () => ({data: []}),
  putImageData() {},
  drawImage() {},
  fillRect() {},
  clearRect() {},
  strokeRect() {},
  beginPath() {},
  closePath() {},
  moveTo() {},
  lineTo() {},
  bezierCurveTo() {},
  quadraticCurveTo() {},
  arc() {},
  rect() {},
  fill() {},
  stroke() {},
  fillText() {},
  strokeText() {},
  clip() {},
  save() {},
  restore() {},
  scale() {},
  rotate() {},
  translate() {},
  transform() {},
  setTransform() {},
  resetTransform() {}
};

if (typeof HTMLCanvasElement !== 'undefined')
  HTMLCanvasElement.prototype.getContext = (() => context) as never;
if (typeof window !== 'undefined' && !window.matchMedia)
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {},
      dispatchEvent: () => false
    })
  });
