import { isExpired, Sensitivity } from "./protocol.js";

export class RecentStore {
  constructor({ maxItems = 20, now = () => new Date() } = {}) {
    this.maxItems = maxItems;
    this.now = now;
    this.items = [];
  }

  add(clip) {
    this.prune();

    if (clip.sensitivity === Sensitivity.SENSITIVE) {
      return;
    }

    this.items = [clip, ...this.items.filter((item) => item.id !== clip.id)].slice(0, this.maxItems);
  }

  list() {
    this.prune();
    return [...this.items];
  }

  prune() {
    const now = this.now();
    this.items = this.items.filter((item) => !isExpired(item, now));
  }
}
