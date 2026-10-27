import * as React from 'react'
import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg text-sm font-medium transition-colors disabled:pointer-events-none disabled:opacity-45 outline-none focus-visible:ring-2 focus-visible:ring-ring [&_svg]:size-4 shrink-0',
  { variants: {
    variant: { default: 'bg-primary text-primary-foreground hover:bg-primary/90', outline: 'border border-input bg-background hover:bg-accent', ghost: 'hover:bg-accent text-muted-foreground hover:text-foreground', secondary: 'bg-secondary text-secondary-foreground hover:bg-secondary/80' },
    size: { default: 'h-9 px-3', sm: 'h-8 px-2.5 text-xs', icon: 'size-9' },
  }, defaultVariants: { variant: 'default', size: 'default' } },
)
function Button({ className, variant, size, asChild = false, ...props }: React.ComponentProps<'button'> & VariantProps<typeof buttonVariants> & { asChild?: boolean }) {
  const Comp = asChild ? Slot : 'button'
  return <Comp data-slot="button" className={cn(buttonVariants({ variant, size, className }))} {...props} />
}
export { Button, buttonVariants }
