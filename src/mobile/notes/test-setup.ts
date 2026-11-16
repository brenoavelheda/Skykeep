/**
 * What jsdom leaves out and the editor measures: where a range or an element sits on screen.
 * Nothing in the tests depends on the numbers, only on the calls not throwing.
 */
const none = () => ({ x: 0, y: 0, top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0, toJSON: () => ({}) }) as DOMRect;
const rects = () => Object.assign([] as DOMRect[], { item: () => null }) as unknown as DOMRectList;
if (!Range.prototype.getBoundingClientRect) Range.prototype.getBoundingClientRect = none;
if (!Range.prototype.getClientRects) Range.prototype.getClientRects = rects;
if (!Element.prototype.scrollIntoView) Element.prototype.scrollIntoView = () => {};
// The page is in English, as the rest of the Android app is.
Object.defineProperty(window.navigator, "languages", { value: ["en-US"], configurable: true });
