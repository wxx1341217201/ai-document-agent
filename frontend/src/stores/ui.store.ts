import { create } from 'zustand';

interface CurrentKnowledgeBase {
  id: number;
  name: string;
}

interface UiStore {
  sidebarCollapsed: boolean;
  currentKnowledgeBase?: CurrentKnowledgeBase;
  setSidebarCollapsed: (collapsed: boolean) => void;
  toggleSidebar: () => void;
  setCurrentKnowledgeBase: (knowledgeBase?: CurrentKnowledgeBase) => void;
}

export const useUiStore = create<UiStore>((set) => ({
  sidebarCollapsed: false,
  currentKnowledgeBase: undefined,
  setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
  toggleSidebar: () => set((state) => ({ sidebarCollapsed: !state.sidebarCollapsed })),
  setCurrentKnowledgeBase: (currentKnowledgeBase) => set({ currentKnowledgeBase }),
}));
