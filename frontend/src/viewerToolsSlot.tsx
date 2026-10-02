import { createContext, useContext } from 'react'

/** DOM host in the viewer top strip for active-pane tools (not over the image). */
export const ViewerToolsSlotContext = createContext<HTMLElement | null>(null)

export function useViewerToolsSlot() {
  return useContext(ViewerToolsSlotContext)
}
