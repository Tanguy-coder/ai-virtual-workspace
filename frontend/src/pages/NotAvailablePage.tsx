import { Empty, PageHeader } from "../components/ui";

export function NotAvailablePage({ feature, increment }: { feature: string; increment: number }) {
  return (
    <div className="max-w-3xl">
      <PageHeader title={feature} />
      <Empty title="Not available yet">
        {feature} is planned for increment {increment} of the delivery plan. Nothing is shown here until it is
        built: this screen never displays placeholder data.
      </Empty>
    </div>
  );
}
