import React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';

const badgeVariants = cva(
  'inline-flex items-center gap-1 font-semibold leading-none shrink-0',
  {
    variants: {
      variant: {
        default: '',
        primary: '',
        success: '',
        warning: '',
        destructive: '',
        info: '',
        accent: '',
      },
      style: {
        subtle: '',
        outline: '',
        solid: '',
      },
      shape: {
        rounded: 'rounded',
        pill: 'rounded-full',
      },
      size: {
        sm: 'px-1.5 py-0.5 text-caption',
        md: 'px-2 py-0.5 text-caption',
      },
    },
    compoundVariants: [
      { variant: 'default', style: 'subtle', className: 'text-muted-foreground bg-muted border border-border' },
      { variant: 'default', style: 'outline', className: 'text-muted-foreground border border-border' },
      { variant: 'default', style: 'solid', className: 'text-foreground bg-accent border border-accent' },
      { variant: 'primary', style: 'subtle', className: 'text-primary bg-primary/10 border border-primary/10' },
      { variant: 'primary', style: 'outline', className: 'text-primary border border-primary/30' },
      { variant: 'primary', style: 'solid', className: 'text-primary-foreground bg-primary border border-primary' },
      { variant: 'success', style: 'subtle', className: 'text-success bg-success/10 border border-success/20' },
      { variant: 'success', style: 'outline', className: 'text-success border border-success/30' },
      { variant: 'success', style: 'solid', className: 'text-success-foreground bg-success border border-success dark:bg-palette-success-300 dark:border-palette-success-300' },
      { variant: 'warning', style: 'subtle', className: 'text-palette-warning-600 dark:text-palette-warning-700 bg-warning/10 border border-warning/20' },
      { variant: 'warning', style: 'outline', className: 'text-palette-warning-600 dark:text-palette-warning-700 border border-warning/30' },
      { variant: 'warning', style: 'solid', className: 'text-warning-foreground bg-warning border border-warning' },
      { variant: 'destructive', style: 'subtle', className: 'text-destructive bg-destructive/10 border border-destructive/20' },
      { variant: 'destructive', style: 'outline', className: 'text-destructive border border-destructive/30' },
      { variant: 'destructive', style: 'solid', className: 'text-destructive-foreground bg-destructive border border-destructive dark:bg-palette-danger-300 dark:border-palette-danger-300' },
      { variant: 'info', style: 'subtle', className: 'text-info bg-info/10 border border-info/20' },
      { variant: 'info', style: 'outline', className: 'text-info border border-info/30' },
      { variant: 'info', style: 'solid', className: 'text-info-foreground bg-info border border-info dark:bg-palette-info-300 dark:border-palette-info-300' },
      { variant: 'accent', style: 'subtle', className: 'text-muted-foreground bg-accent border border-border' },
      { variant: 'accent', style: 'outline', className: 'text-muted-foreground border border-accent' },
      { variant: 'accent', style: 'solid', className: 'text-foreground bg-accent border border-accent' },
    ],
    defaultVariants: {
      variant: 'default',
      style: 'subtle',
      shape: 'rounded',
      size: 'md',
    },
  },
);

interface BadgeProps
  extends Omit<React.HTMLAttributes<HTMLSpanElement>, 'style'>,
    VariantProps<typeof badgeVariants> {
  uppercase?: boolean;
  icon?: React.ReactNode;
}

export function Badge({
  variant,
  style,
  shape,
  size,
  uppercase = false,
  icon,
  className,
  children,
  ...rest
}: BadgeProps) {
  return (
    <span
      className={badgeVariants({ variant, style, shape, size, className }) +
        (uppercase ? ' uppercase tracking-[0.2em]' : '')}
      {...rest}
    >
      {icon}
      {children}
    </span>
  );
}
